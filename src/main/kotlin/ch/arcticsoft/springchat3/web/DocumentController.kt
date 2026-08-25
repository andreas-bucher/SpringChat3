package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.document.DocumentIndex
import ch.arcticsoft.springchat3.document.DocumentDeletionService
import ch.arcticsoft.springchat3.document.DocumentMoveOutcome
import ch.arcticsoft.springchat3.document.DocumentMoveService
import ch.arcticsoft.springchat3.document.DocumentStore
import ch.arcticsoft.springchat3.document.DocumentStructureExtractor
import ch.arcticsoft.springchat3.document.DocumentStructureStore
import ch.arcticsoft.springchat3.document.DocumentSummary
import ch.arcticsoft.springchat3.document.DriveLinkStore
import ch.arcticsoft.springchat3.document.PdfConversionException
import ch.arcticsoft.springchat3.document.PdfPreviewService
import ch.arcticsoft.springchat3.document.PdfTextExtractor
import ch.arcticsoft.springchat3.document.WebPageStore
import ch.arcticsoft.springchat3.document.WordDocumentStore
import ch.arcticsoft.springchat3.document.WorkingDocumentStore
import ch.arcticsoft.springchat3.project.SpaceAccess
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * Reactive (WebFlux) entry point for the document-Q&A feature (2026-08-22) -
 * see springchat3_document_qa.md in project memory for the full design.
 * Separate from [ChatController] since this is a distinct concern (file
 * upload/extraction vs. chat turns), even though both feed into the same
 * [ch.arcticsoft.springchat3.agent.ChatAgent].
 *
 * `@RequestPart("file") filePart: FilePart` (a direct, non-`Mono`-wrapped
 * parameter type) is Spring WebFlux's documented way to bind one multipart
 * part - Spring resolves it asynchronously before invoking this method, the
 * same way `@RequestBody` works for a non-reactive parameter type elsewhere.
 * This specific binding shape hasn't been build-verified in this app yet
 * (unlike [PdfTextExtractor]'s Spring AI API, which was checked against
 * Spring AI's own docs before writing) - it's long-standing, stable Spring
 * Framework core API, not a fast-moving library, so confidence is high, but
 * flag this first if `./gradlew compileKotlin` fails here.
 *
 * [upload]'s optional `spaceId` (2026-08-23, user's own request "when
 * uploading a file... save the files in the project folder of the active
 * project" - see springchat3_projects_panel.md in project memory) is a plain
 * `?spaceId=...` query parameter, not a second multipart part - deliberately,
 * to avoid needing to verify how Spring WebFlux binds a plain *text*
 * multipart part (unclear/unconfirmed, unlike the well-established
 * `FilePart` binding above), when a query parameter alongside a multipart
 * body is ordinary, unambiguous `@RequestParam` binding with no such
 * uncertainty.
 */
/**
 * `PATCH /documents/{id}/space`'s body. [spaceId] is nullable so the unscoped
 * bucket is expressible, matching every other spaceId in this app; the UI only
 * ever sends a real space.
 */
data class MoveDocumentRequest(val spaceId: String?)

@RestController
class DocumentController(
    private val documentStore: DocumentStore,
    private val pdfTextExtractor: PdfTextExtractor,
    private val documentIndex: DocumentIndex,
    private val documentStructureExtractor: DocumentStructureExtractor,
    private val documentStructureStore: DocumentStructureStore,
    private val driveLinkStore: DriveLinkStore,
    private val workingDocumentStore: WorkingDocumentStore,
    private val webPageStore: WebPageStore,
    private val wordDocumentStore: WordDocumentStore,
    private val pdfPreviewService: PdfPreviewService,
    private val spaceAccess: SpaceAccess,
    private val documentDeletionService: DocumentDeletionService,
    private val documentMoveService: DocumentMoveService,
) {
    private val log = LoggerFactory.getLogger(DocumentController::class.java)

    companion object {
        /**
         * Hard cap on an uploaded PDF's size, checked against the joined
         * in-memory byte array before it ever reaches PDFBox. Generous for
         * the kind of document a single user is likely to attach (a report,
         * a manual, a paper), while still bounding how much memory one
         * upload can claim - this app has no queueing/backpressure beyond
         * WebFlux's own request handling, so an unbounded upload size is a
         * real (if unlikely, single-user-app) risk worth a simple cap.
         * Bump this if a real document gets rejected that shouldn't be.
         */
        private const val MAX_PDF_BYTES = 20 * 1024 * 1024 // 20 MB

        /**
         * OOXML's own media type for a `.docx` - served by [file] for an
         * uploaded Word document (2026-08-23, see [WordDocumentStore]),
         * alongside `Content-Disposition: attachment` rather than the
         * `inline` a PDF gets: no browser renders a Word file in a tab, so
         * `inline` would just produce a download with a worse filename.
         */
        private const val DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestPart("file") filePart: FilePart,
        @RequestParam(required = false) spaceId: String?,
        exchange: ServerWebExchange,
    ): Mono<DocumentSummary> {
        // Before a single byte is read, not after: a viewer uploading a 20MB
        // PDF should be turned away, not have it parsed and then rejected.
        spaceAccess.requireWrite(exchange, spaceId)
        return DataBufferUtils.join(filePart.content())
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                DataBufferUtils.release(buffer)
                bytes
            }
            .flatMap { bytes ->
                if (bytes.size > MAX_PDF_BYTES) {
                    Mono.error(IllegalArgumentException("PDF too large (${bytes.size} bytes, max $MAX_PDF_BYTES)"))
                } else {
                    // PDFBox parsing is blocking CPU/IO work - shift it off
                    // the Netty event-loop thread, same pattern
                    // ChatController.invoke uses for Embabel's own blocking
                    // call. Parsed once (extractPages), not twice: the
                    // joined `text` (display-only character count, see
                    // DocumentStore.ExtractedDocument's doc comment) and the
                    // page-level Documents (documentIndex.index, for
                    // chunking + embedding - "Phase 2", see
                    // springchat3_document_qa.md in project memory) both
                    // come from the same PDFBox parse.
                    Mono.fromCallable {
                        val pages = pdfTextExtractor.extractPages(bytes)
                        val text = pages.joinToString("\n\n") { it.text.orEmpty() }
                        val documentId = documentStore.store(filePart.filename(), text, bytes, spaceId)
                        documentIndex.index(documentId, pages)
                        // Structure extraction (2026-08-22, see
                        // springchat3_document_qa.md in project memory) reads
                        // the same already-in-memory `bytes` directly via
                        // PDFBox (see DocumentStructureExtractor) rather than
                        // reusing `pages` above - it needs the PDF's outline
                        // tree, which PagePdfDocumentReader never exposes.
                        // Stored only when the PDF actually has an embedded
                        // outline; absent otherwise, same as any document
                        // that predates this feature (ChatAgent.answer falls
                        // back to vector search for both cases identically).
                        documentStructureExtractor.extractStructure(bytes)?.let { structure ->
                            documentStructureStore.store(documentId, structure)
                        }
                        log.info(
                            "Uploaded document '{}' ({} bytes -> {} extracted chars, {} pages indexed) as {}",
                            filePart.filename(),
                            bytes.size,
                            text.length,
                            pages.size,
                            documentId,
                        )
                        DocumentSummary(documentId, filePart.filename(), text.length, spaceId)
                    }.subscribeOn(Schedulers.boundedElastic())
                }
            }
    }

    /**
     * Backs the side panel's "Uploaded Documents" list (added 2026-08-22,
     * see springchat3_document_qa.md in project memory) - every currently
     * stored document's metadata, oldest upload first, EXCLUDING anything
     * synced in from any linked Google Drive folder (2026-08-22, user's own
     * request "File loaded from Google Drive do not need to be enlisted as
     * Documents" - see springchat3_google_drive.md in project memory; "any"
     * since a later same-day change allows more than one folder to be
     * linked at once) OR any individually-linked Google Doc (2026-08-22,
     * "Working Documents" - see springchat3_working_documents.md in project
     * memory, same reasoning: it belongs in its own section, not this one)
     * OR any linked web page (2026-08-23, "Web Pages" - see
     * springchat3_projects_panel.md in project memory, same reasoning again:
     * it belongs in the "Web Pages" section, not this one) OR any uploaded
     * Word document (2026-08-23, see [WordDocumentStore] - it belongs in
     * the "Working Documents" section alongside the linked Google Docs,
     * per the user's own choice when asked where an upload should be
     * listed).
     * [DocumentStore] itself makes no such distinction - a Drive-sourced,
     * linked-Doc, or linked-web-page document is stored there exactly like
     * an uploaded one, since all go through the same ingestion pipeline (see
     * [ch.arcticsoft.springchat3.web.DriveController]'s own doc comment) -
     * so the filtering has to happen here, against [DriveLinkStore]'s,
     * [WorkingDocumentStore]'s, and [WebPageStore]'s own separate
     * bookkeeping of which `documentId`s came from each. Without it, such a
     * file would show up twice: once here (labeled as if uploaded) and once
     * in its own "Google Drive" folder card, "Working Documents" row, or
     * "Web Pages" row. A plain (non-`Mono`) return type is fine here: every
     * store involved only ever does a fast in-memory read, so there's no
     * blocking work to shift off the Netty event-loop thread the way
     * [upload]'s PDFBox parsing needs.
     */
    @GetMapping("/documents")
    fun list(exchange: ServerWebExchange): List<DocumentSummary> {
        val driveDocumentIds = driveLinkStore.getAll().flatMap { it.files }.map { it.documentId }.toSet()
        val workingDocumentIds = workingDocumentStore.getAll().map { it.documentId }.toSet()
        val webPageDocumentIds = webPageStore.getAll().map { it.documentId }.toSet()
        val wordDocumentIds = wordDocumentStore.getAll().map { it.documentId }.toSet()
        // canRead per row rather than `it.spaceId in visibleSpaceIds`: a
        // document with a null spaceId predates spaces entirely and belongs
        // to no space to be a member of, but is still readable by everyone
        // (see SpaceAccess's legacy rules).
        return documentStore.list().filter { spaceAccess.canRead(exchange, it.spaceId) }.filterNot {
            it.documentId in driveDocumentIds ||
                it.documentId in workingDocumentIds ||
                it.documentId in webPageDocumentIds ||
                it.documentId in wordDocumentIds
        }
    }

    /**
     * Serves [id]'s original PDF bytes, for the side panel's "open in a new
     * tab" button per document (2026-08-22, user's own idea "would it be
     * possible to enable the files to be displayed on another browser tab?"
     * - see springchat3_document_qa.md in project memory). Works identically
     * for an uploaded document or a Drive-synced one, since both are stored
     * through [DocumentStore] the same way (see [list]'s own doc comment).
     *
     * `404 Not Found` both when [id] doesn't match any stored document AND
     * when it does but simply has no raw bytes on disk - a document stored
     * before this feature existed, see [DocumentStore.getBytes]'s own doc
     * comment. Deliberately not distinguished from a plain "no such
     * document" 404: either way there's nothing to show, and a document
     * old enough to lack stored bytes just needs re-uploading (or, for a
     * Drive-sourced one, re-syncing) to pick this feature up, same as any
     * document from before a schema/feature change in this app generally
     * would.
     *
     * `Content-Disposition: inline` (not the `attachment` a browser
     * defaults to for an unrecognized disposition) so the new tab renders
     * the PDF itself rather than downloading it - `filename` there is just
     * a courtesy for if the user saves it from that tab, has no effect on
     * inline rendering.
     */
    @GetMapping("/documents/{id}/file")
    fun file(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<ByteArray> {
        val document = documentStore.get(id) ?: return ResponseEntity.notFound().build()
        // The space isn't in the URL, so it has to be resolved from the
        // document itself - without this, any signed-in user could read any
        // document by guessing or keeping an id (2026-08-24, see SpaceAccess).
        spaceAccess.requireRead(exchange, document.spaceId)
        val bytes = documentStore.getBytes(id) ?: return ResponseEntity.notFound().build()
        val isWord = document.rawFilename.endsWith(".docx", ignoreCase = true)
        val disposition =
            if (isWord) ContentDisposition.attachment() else ContentDisposition.inline()
        return ResponseEntity.ok()
            .contentType(if (isWord) MediaType.parseMediaType(DOCX_CONTENT_TYPE) else MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(document.filename).build().toString())
            .body(bytes)
    }

    /**
     * The same document, always as viewable PDF (2026-08-23, user's own
     * request "Let's implement Option 3 using LibreOffice" - see
     * [ch.arcticsoft.springchat3.document.PdfPreviewService] and
     * springchat3_pdf_preview.md in project memory). A Word document is
     * converted through LibreOffice and cached; anything already stored as
     * PDF is served unchanged, so the side panel can point one link at every
     * card instead of branching per document kind.
     *
     * Sits here rather than on [WordDocumentController] for exactly that
     * reason: it serves every kind of document, like [file] next to it, not
     * just the uploaded-Word section.
     *
     * `subscribeOn(boundedElastic())` is not optional - converting means
     * waiting on a subprocess, and doing that on a Netty event-loop thread
     * would stall every other request in the app for the duration.
     *
     * The whole response is built *inside* the callable, rather than mapping
     * over its result, because [PdfPreviewService.preview] returns null for a
     * document with no file to show (a linked web page, or an id that no
     * longer exists). A `fromCallable` over a nullable value infers
     * `Mono<PdfPreview?>`, and every operator downstream then has a nullable
     * receiver - so the null is resolved here, where it means one specific
     * thing (404), and nothing nullable enters the reactive chain at all.
     */
    @GetMapping("/documents/{id}/preview")
    fun preview(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
        // Checked here rather than inside the callable so an unauthorized
        // request never reaches LibreOffice - and so the 403 isn't swallowed
        // by onErrorResume below and reported as a failed conversion.
        documentStore.get(id)?.let { spaceAccess.requireRead(exchange, it.spaceId) }
        return Mono.fromCallable<ResponseEntity<Any>> {
            val preview = pdfPreviewService.preview(id)
            if (preview == null) {
                ResponseEntity.notFound().build()
            } else {
                ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(preview.filename).build().toString(),
                    )
                    // The document behind this URL changes in place when the
                    // agent edits it, and the id doesn't - so the browser has
                    // to revalidate rather than keep showing what it cached
                    // before the edit.
                    .cacheControl(CacheControl.noCache())
                    .body(preview.bytes)
            }
        }
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume { e ->
                val unavailable = e is PdfConversionException && e.unavailable
                log.warn("Could not build a PDF preview for document {}", id, e)
                Mono.just<ResponseEntity<Any>>(
                    ResponseEntity
                        .status(if (unavailable) 503 else 500)
                        .body(
                            mapOf(
                                "message" to (
                                    if (unavailable) "PDF preview is unavailable - LibreOffice is not installed on this server."
                                    else "Could not build a PDF preview for this document."
                                    ),
                            ),
                        ),
                )
            }
    }

    /**
     * Deletes one uploaded document, e.g. from the side panel's per-item ×
     * button. `204 No Content` on success, `404 Not Found` if [id] doesn't
     * match any stored document (already deleted, or never existed) - the
     * frontend treats both as "it's gone" rather than surfacing a 404 as an
     * error. Also removes [id]'s chunks from the vector store
     * ([DocumentIndex.remove], "Phase 2" - see springchat3_document_qa.md in
     * project memory) and its extracted structure, if any
     * ([DocumentStructureStore.remove], "two-stage search" - same memory
     * file) so a deleted document stops being searchable, in either way,
     * immediately rather than lingering there indefinitely; only called
     * alongside an actual [DocumentStore] removal, not for an [id] that was
     * never a real document.
     *
     * Also drops [id] from [DriveLinkStore]'s bookkeeping if it came from a
     * linked Google Drive folder (2026-08-22, see
     * springchat3_google_drive.md in project memory) - a harmless no-op for
     * a directly-uploaded document, which was never tracked there. Without
     * this, a later "Sync now" would keep treating this file as already
     * synced and never re-offer it, even though deleting it here only
     * removes it from this app's index, not from Drive itself. Likewise
     * drops [id] from [WorkingDocumentStore] if it's a linked Google Doc
     * (2026-08-22, "Working Documents" - see
     * springchat3_working_documents.md in project memory) - unlike a
     * Drive *folder*, there's no lighter "unlink but keep the document"
     * case for a single linked Doc (see [WorkingDocumentStore.remove]'s own
     * doc comment), so this same × delete is its only removal path. Likewise
     * drops [id] from [WebPageStore] if it's a linked web page (2026-08-23,
     * "Web Pages" - see springchat3_projects_panel.md in project memory) -
     * same "no lighter unlink-but-keep case" reasoning as a linked Doc, a
     * harmless no-op for any other document type. Likewise drops [id] from
     * [WordDocumentStore] if it was an uploaded Word document (2026-08-23),
     * which - having no remote source at all - has that same single removal
     * path.
     *
     * **Call order (2026-08-23, changed alongside project-scoped document
     * storage): [documentIndex]/[documentStructureStore] are removed BEFORE
     * [documentStore] itself, not after** - see [DocumentStore.remove]'s own
     * doc comment for why this now matters (they resolve their own files via
     * [DocumentStore.documentDir], which needs [id]'s entry to still exist).
     * The existence check that used to be [DocumentStore.remove]'s own
     * return value is now a separate [DocumentStore.get] up front instead.
     * [webPageStore] doesn't need this ordering - it only ever tracks its
     * own `List<LinkedWebPage>`, never resolves anything via
     * [DocumentStore.documentDir], same as [driveLinkStore]/
     * [workingDocumentStore] here.
     */
    /**
     * Re-files [id] into another space (2026-08-25, dragging a document from
     * one space to another).
     *
     * **Write access is required on BOTH ends**, and the source is checked
     * first: taking a document out of a space is a change to that space, and
     * a viewer who could drag one into a space they own would otherwise be
     * able to empty a space they only read. Same reason `PATCH` rather than
     * `POST /documents/{id}/move` - this changes one field of an existing
     * resource, and the id never changes, which is exactly what keeps the
     * document selected and attached to the conversation across the move.
     *
     * A file inside a linked Drive folder is a **409**, not a silent no-op:
     * it is owned by the sync (see [ch.arcticsoft.springchat3.document.DocumentStore.moveToSpace]),
     * and a UI that greys it out still deserves a real answer if it asks.
     */
    @PatchMapping("/documents/{id}/space")
    fun moveToSpace(
        @PathVariable id: String,
        @RequestBody request: MoveDocumentRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<Map<String, String>> {
        val document = documentStore.get(id) ?: return ResponseEntity.notFound().build()
        spaceAccess.requireWrite(exchange, document.spaceId)
        spaceAccess.requireWrite(exchange, request.spaceId)
        return when (documentMoveService.move(id, request.spaceId)) {
            DocumentMoveOutcome.MOVED -> ResponseEntity.noContent().build()
            DocumentMoveOutcome.NOT_FOUND -> ResponseEntity.notFound().build()
            DocumentMoveOutcome.DRIVE_FOLDER_FILE -> ResponseEntity.status(409).body(
                mapOf("message" to "A file inside a linked Drive folder stays with that folder - move the folder instead."),
            )
            DocumentMoveOutcome.FAILED -> ResponseEntity.status(500).body(
                mapOf("message" to "The document could not be moved. It is unchanged."),
            )
        }
    }

    @DeleteMapping("/documents/{id}")
    fun delete(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Void> {
        val document = documentStore.get(id) ?: return ResponseEntity.notFound().build()
        spaceAccess.requireWrite(exchange, document.spaceId)
        // The sequence itself (and the order that matters within it) moved to
        // DocumentDeletionService on 2026-08-24, so that deleting a whole
        // space cleans a document up in exactly the same way this does.
        documentDeletionService.delete(id)
        return ResponseEntity.noContent().build()
    }
}

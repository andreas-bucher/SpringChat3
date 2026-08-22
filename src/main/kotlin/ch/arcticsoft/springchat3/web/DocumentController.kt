package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.document.DocumentIndex
import ch.arcticsoft.springchat3.document.DocumentStore
import ch.arcticsoft.springchat3.document.DocumentStructureExtractor
import ch.arcticsoft.springchat3.document.DocumentStructureStore
import ch.arcticsoft.springchat3.document.DocumentSummary
import ch.arcticsoft.springchat3.document.DriveLinkStore
import ch.arcticsoft.springchat3.document.PdfTextExtractor
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
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
 */
@RestController
class DocumentController(
    private val documentStore: DocumentStore,
    private val pdfTextExtractor: PdfTextExtractor,
    private val documentIndex: DocumentIndex,
    private val documentStructureExtractor: DocumentStructureExtractor,
    private val documentStructureStore: DocumentStructureStore,
    private val driveLinkStore: DriveLinkStore,
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
    }

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@RequestPart("file") filePart: FilePart): Mono<DocumentSummary> =
        DataBufferUtils.join(filePart.content())
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
                        val documentId = documentStore.store(filePart.filename(), text, bytes)
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
                        DocumentSummary(documentId, filePart.filename(), text.length)
                    }.subscribeOn(Schedulers.boundedElastic())
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
     * linked at once). [DocumentStore] itself makes no such distinction - a
     * Drive-sourced document is stored there exactly like an uploaded one,
     * since it goes through the same ingestion pipeline (see
     * [ch.arcticsoft.springchat3.web.DriveController]'s own doc comment) -
     * so the filtering has to happen here, against [DriveLinkStore]'s own separate
     * bookkeeping of which `documentId`s came from Drive. Without it, a
     * Drive-synced file would show up twice: once here (labeled as if
     * uploaded) and once in its own "Google Drive" folder card. A plain
     * (non-`Mono`) return type is fine here: both stores only ever do a
     * fast in-memory read, so there's no blocking work to shift off the
     * Netty event-loop thread the way [upload]'s PDFBox parsing needs.
     */
    @GetMapping("/documents")
    fun list(): List<DocumentSummary> {
        val driveDocumentIds = driveLinkStore.getAll().flatMap { it.files }.map { it.documentId }.toSet()
        return documentStore.list().filterNot { it.documentId in driveDocumentIds }
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
    fun file(@PathVariable id: String): ResponseEntity<ByteArray> {
        val document = documentStore.get(id) ?: return ResponseEntity.notFound().build()
        val bytes = documentStore.getBytes(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(document.filename).build().toString())
            .body(bytes)
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
     * removes it from this app's index, not from Drive itself.
     */
    @DeleteMapping("/documents/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> =
        if (documentStore.remove(id)) {
            documentIndex.remove(id)
            documentStructureStore.remove(id)
            driveLinkStore.untrackDocument(id)
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
}

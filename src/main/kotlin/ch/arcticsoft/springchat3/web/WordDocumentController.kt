package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.document.DocumentIndex
import ch.arcticsoft.springchat3.document.DocumentStore
import ch.arcticsoft.springchat3.document.PdfPreviewService
import ch.arcticsoft.springchat3.document.UploadedWordDocument
import ch.arcticsoft.springchat3.document.WordDocumentStore
import ch.arcticsoft.springchat3.document.WordTextExtractor
import ch.arcticsoft.springchat3.project.SpaceAccess
import ch.arcticsoft.springchat3.settings.SettingsResolver
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * What the frontend renders for one uploaded Word document - the same
 * "store the link/upload bookkeeping separately, read the display fields
 * back out of [DocumentStore]" split [WebPageStatus] uses: [filename] and
 * [characterCount] come from the stored [ch.arcticsoft.springchat3.document.ExtractedDocument],
 * not from [UploadedWordDocument] itself, so there's exactly one copy of
 * each and no way for them to drift.
 */
data class WordDocumentStatus(
    val documentId: String,
    val filename: String,
    val characterCount: Int,
    val uploadedAt: Long,
    val spaceId: String? = null,
    /**
     * Whether **this caller** has unlocked the document for the agent to edit
     * (2026-08-25) - see
     * [ch.arcticsoft.springchat3.settings.UserSettings.editableDocumentIds]
     * for why the unlock is per user. Only Word documents carry it: nothing
     * else in this app can be edited by the agent at all, so a padlock on a
     * PDF card would imply a risk that does not exist.
     */
    val editable: Boolean = false,
)

/**
 * Uploading a Microsoft Word document into the right panel's "Working
 * Documents" section (2026-08-23, user's own request "on the right panel
 * under Working Documents, below the box to link Google Docs, add a 2nd box
 * to upload MS Word document") - see [WordDocumentStore] for why an upload
 * is its own store rather than a variant of a linked Google Doc.
 *
 * Its own controller rather than another route on [DocumentController]:
 * that one is the "Uploaded Documents" (PDF) section's own endpoint set,
 * and this is a different section with a different store, different
 * extractor and a different list endpoint - same separation
 * [WebPageController] already has. Deletion is the exception and stays
 * shared: `DELETE /documents/{id}` already removes a document from every
 * per-source store at once (see that method's doc comment), so there's no
 * `DELETE /word-documents/{id}` here.
 *
 * The multipart binding (`@RequestPart("file") filePart: FilePart` plus the
 * `spaceId` as a plain query parameter rather than a second, text
 * multipart part) is copied deliberately from [DocumentController.upload] -
 * that exact shape is build-verified in this app, and this endpoint has no
 * reason to explore a different one.
 */
@RestController
class WordDocumentController(
    private val wordDocumentStore: WordDocumentStore,
    private val documentStore: DocumentStore,
    private val documentIndex: DocumentIndex,
    private val wordTextExtractor: WordTextExtractor,
    private val pdfPreviewService: PdfPreviewService,
    private val spaceAccess: SpaceAccess,
    private val settingsResolver: SettingsResolver,
) {
    private val log = LoggerFactory.getLogger(WordDocumentController::class.java)

    companion object {
        /** Same cap, for the same reasons, as [DocumentController]'s own `MAX_PDF_BYTES`. */
        private const val MAX_WORD_BYTES = 20 * 1024 * 1024 // 20 MB

        /**
         * `.docx` only, per the user's own choice when asked (2026-08-23) -
         * the legacy binary `.doc` format would work through the same Tika
         * extractor untouched, so widening this is a one-line change plus
         * the matching `accept` attribute in index.html if it's ever wanted.
         */
        private const val DOCX_EXTENSION = ".docx"

        /**
         * The name [DocumentStore] persists this upload's raw bytes under,
         * instead of its default `document.pdf` - see
         * [ch.arcticsoft.springchat3.document.ExtractedDocument.rawFilename].
         * [DocumentController.file] reads the extension back off it to pick
         * the right `Content-Type`, so the two have to agree.
         */
        const val RAW_DOCX_FILENAME = "document.docx"
    }

    private fun statusFor(doc: UploadedWordDocument): WordDocumentStatus? =
        documentStore.get(doc.documentId)?.let {
            WordDocumentStatus(doc.documentId, it.filename, it.text.length, doc.uploadedAt, doc.spaceId)
        }

    @GetMapping("/word-documents")
    fun list(exchange: ServerWebExchange): List<WordDocumentStatus> {
        // Resolved once for the whole list rather than per row - it is one
        // read of this caller's own settings, not a per-document lookup.
        val unlocked = settingsResolver.editableDocumentIdsFor(spaceAccess.currentUserEmail(exchange))
        return wordDocumentStore.getAll()
            .filter { spaceAccess.canRead(exchange, it.spaceId) }
            .mapNotNull { statusFor(it)?.copy(editable = it.documentId in unlocked) }
    }

    /**
     * Rejects a non-`.docx` filename up front rather than letting Tika try
     * (and quite possibly succeed, since it happily parses PDFs, HTML and
     * plain text too) - the box the user dropped this into says "Word
     * document", and a PDF that silently landed in the Working Documents
     * section instead of the Uploaded Documents one above would be a
     * confusing surprise, not a convenience.
     */
    @PostMapping("/word-documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestPart("file") filePart: FilePart,
        @RequestParam(required = false) spaceId: String?,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Any>> {
        spaceAccess.requireWrite(exchange, spaceId)
        val filename = filePart.filename()
        if (!filename.endsWith(DOCX_EXTENSION, ignoreCase = true)) {
            return Mono.just(badRequest("Only Word .docx files can be uploaded here."))
        }
        return DataBufferUtils.join(filePart.content())
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                DataBufferUtils.release(buffer)
                bytes
            }
            .flatMap { bytes ->
                if (bytes.size > MAX_WORD_BYTES) {
                    Mono.just(badRequest("\"$filename\" is too large (${bytes.size} bytes, max $MAX_WORD_BYTES)."))
                } else {
                    // Tika parsing is blocking CPU/IO work, same as PDFBox in
                    // DocumentController.upload - off the Netty event loop.
                    Mono.fromCallable { ingest(filename, bytes, spaceId) }
                        .subscribeOn(Schedulers.boundedElastic())
                        .map<ResponseEntity<Any>> { doc ->
                            statusFor(doc)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.internalServerError().build()
                        }
                }
            }
            .onErrorResume { e ->
                log.warn("Could not upload Word document '{}'", filename, e)
                Mono.just(badRequest("Could not read \"$filename\" - is it a valid Word .docx file?"))
            }
    }

    private fun badRequest(message: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(mapOf("message" to message))

    /**
     * No `existingDocumentId` removal step of the kind
     * [WebPageController.ingestWebPage] needs - an upload never replaces a
     * previous version of itself (see [WordDocumentStore]'s own doc
     * comment), so nothing is being superseded here.
     */
    private fun ingest(filename: String, bytes: ByteArray, spaceId: String?): UploadedWordDocument {
        val extracted = wordTextExtractor.extract(bytes, filename)
        val text = extracted.joinToString("\n\n") { it.text.orEmpty() }
        check(text.isNotBlank()) { "No text could be extracted from $filename" }
        val documentId = documentStore.store(filename, text, bytes, spaceId, rawFilename = RAW_DOCX_FILENAME)
        documentIndex.index(documentId, extracted)
        // Fire-and-forget, per the user's own choice of when the PDF should
        // exist ("after word document is imported"). Not awaited: the card
        // should appear the moment the upload lands, and a preview that
        // failed to pre-build is rebuilt on first view anyway - see
        // PdfPreviewService.
        pdfPreviewService.warm(documentId)
        log.info("Uploaded Word document '{}' ({} bytes -> {} extracted chars) as {}", filename, bytes.size, text.length, documentId)
        val doc = UploadedWordDocument(documentId, filename, System.currentTimeMillis(), spaceId)
        wordDocumentStore.add(doc)
        return doc
    }
}

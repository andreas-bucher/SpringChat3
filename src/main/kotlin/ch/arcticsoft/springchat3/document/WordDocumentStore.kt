package ch.arcticsoft.springchat3.document

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/**
 * One uploaded Microsoft Word document (2026-08-23, user's own request "on
 * the right panel under Working Documents, below the box to link Google
 * Docs, add a 2nd box to upload MS Word document") - the third kind of
 * "Working Document" alongside a linked Google Doc ([LinkedGoogleDoc]),
 * listed in the same right-panel section but with no live source to resync
 * from: a `.docx` is uploaded once, extracted, and that's it. Re-uploading
 * the same file simply produces a second, independent entry, exactly as
 * re-uploading the same PDF twice already does - there's no stable
 * remote-file identity here to deduplicate against the way
 * [LinkedGoogleDoc.driveFileId] gives a linked Doc one.
 *
 * Deliberately its own store rather than a variant of [LinkedGoogleDoc]:
 * every field that makes a linked Doc a *link* (`driveFileId`,
 * `lastSyncedAt`) is meaningless for an upload, and folding the two
 * together would mean nullable-everything plus a discriminator on a type
 * whose whole point is that it IS a Drive link. The frontend renders both
 * lists into the one "Working Documents" section instead - see
 * renderWorkingDocsSection() in index.html.
 *
 * The raw `.docx` bytes are kept (unlike a linked web page, which stores
 * none) so the row's own arrow icon can hand the original file back -
 * [DocumentStore] persists them under a `document.docx` filename rather
 * than its usual `document.pdf`, see [ExtractedDocument.rawFilename].
 *
 * [projectId] is the project that was active at upload time, or null for
 * none - same "fixed at ingest time" treatment every other document source
 * in this app gets (see [DriveLink.projectId] / [LinkedGoogleDoc.projectId] /
 * [LinkedWebPage.projectId]); with nothing to resync there's no later pass
 * that could disagree with it.
 */
data class UploadedWordDocument(
    val documentId: String,
    val filename: String,
    val uploadedAt: Long,
    val projectId: String? = null,
)

/**
 * Persists every uploaded Word document to
 * `[data-dir]/word-documents.json` - same write-through, single-shared-file
 * JSON pattern [WorkingDocumentStore]/[WebPageStore] use, and the same
 * corrupt-file-at-startup behavior (log a warning, start empty, never fail
 * application startup).
 *
 * Keyed by [UploadedWordDocument.documentId] throughout, unlike
 * [WorkingDocumentStore] (keyed by Drive's own file id) or [WebPageStore]
 * (keyed by url) - both of those need a key that survives a resync
 * replacing the document behind it, which is exactly what an upload with no
 * resync path never does.
 */
@Component
class WordDocumentStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
) {
    private val log = LoggerFactory.getLogger(WordDocumentStore::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var docs: List<UploadedWordDocument> = loadPersisted()

    private fun storeFile() = File(dataDir, "word-documents.json")

    private fun loadPersisted(): List<UploadedWordDocument> {
        val file = storeFile()
        if (!file.exists()) return emptyList()
        return try {
            objectMapper.readValue<List<UploadedWordDocument>>(file)
        } catch (e: Exception) {
            log.warn("Could not load persisted Word documents from {} - starting with none", file, e)
            emptyList()
        }
    }

    private fun persist() {
        try {
            val file = storeFile()
            file.parentFile?.mkdirs()
            objectMapper.writeValue(file, docs)
        } catch (e: Exception) {
            log.warn("Could not persist Word documents to {}", storeFile(), e)
        }
    }

    fun getAll(): List<UploadedWordDocument> = docs

    fun get(documentId: String): UploadedWordDocument? = docs.find { it.documentId == documentId }

    fun add(doc: UploadedWordDocument) {
        docs = docs + doc
        persist()
    }

    /**
     * Drops [documentId] from this store, a no-op for an id that was never
     * an uploaded Word document - called by
     * [ch.arcticsoft.springchat3.web.DocumentController.delete] alongside
     * the other per-source stores' own equivalents. Same "no lighter
     * unlink-but-keep case" reasoning [WorkingDocumentStore.remove] and
     * [WebPageStore.remove] both document: the document exists *because* of
     * this entry, so the shared × delete is its only removal path.
     */
    fun remove(documentId: String) {
        val updated = docs.filterNot { it.documentId == documentId }
        if (updated.size != docs.size) {
            docs = updated
            persist()
        }
    }
}

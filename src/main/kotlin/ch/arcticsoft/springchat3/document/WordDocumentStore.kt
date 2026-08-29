package ch.arcticsoft.springchat3.document

import org.springframework.stereotype.Component

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
 * [spaceId] is the project that was active at upload time, or null for
 * none - same "fixed at ingest time" treatment every other document source
 * in this app gets (see [DriveLink.spaceId] / [LinkedGoogleDoc.spaceId] /
 * [LinkedWebPage.spaceId]); with nothing to resync there's no later pass
 * that could disagree with it.
 */
data class UploadedWordDocument(
    val documentId: String,
    val filename: String,
    val uploadedAt: Long,
    val spaceId: String? = null,
)

/**
 * Persists every uploaded Word document to `word-documents.json` **inside
 * each space's own folder** (2026-08-24) - see [SpaceScopedJsonStore] for
 * the layout, the unassigned bucket and why there's no migration off the old
 * single `[data-dir]/word-documents.json`. Same write-through, load-once
 * pattern [WorkingDocumentStore]/[WebPageStore] use, and the same
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
    private val spaceScopedStore: SpaceScopedJsonStore,
) {
    @Volatile
    private var docs: List<UploadedWordDocument> = spaceScopedStore.load(STORE_FILENAME, UploadedWordDocument::class.java)

    private fun persist() = spaceScopedStore.persist(STORE_FILENAME, docs) { it.spaceId }

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
    /**
     * Re-files [documentId] under [spaceId], a no-op for an id that is not a
     * uploaded Word document (2026-08-25, moving a document between spaces -
     * see [ch.arcticsoft.springchat3.document.DocumentMoveService]). The row
     * itself does not move between files here: [persist] rewrites every
     * space's file from the whole list keyed on each entry's own spaceId, so
     * changing this one field is what relocates it on disk.
     */
    fun setSpace(documentId: String, spaceId: String?) {
        val index = docs.indexOfFirst { it.documentId == documentId }
        if (index < 0) return
        docs = docs.toMutableList().also { it[index] = it[index].copy(spaceId = spaceId) }
        persist()
    }

    /**
     * Renames [documentId], a no-op for an id that is not an uploaded Word
     * document (2026-08-28). The row here and
     * [ch.arcticsoft.springchat3.document.ExtractedDocument.filename] are
     * two copies of the same name - a `.docx` upload writes both - so
     * [ch.arcticsoft.springchat3.web.DocumentController.rename] sets both,
     * and this one being a no-op for a PDF is what lets it do so
     * unconditionally.
     *
     * The agent resolves a document to edit BY FILENAME
     * ([ch.arcticsoft.springchat3.document.WordDocumentWorkspace.resolve],
     * and [ch.arcticsoft.springchat3.tools.WordDocumentEditTool.targetedByUser]
     * checks the user named it), and both read this store live - so a rename
     * changes what the user has to say to point at this document, with no
     * stale copy left behind.
     */
    fun rename(documentId: String, filename: String) {
        val index = docs.indexOfFirst { it.documentId == documentId }
        if (index < 0) return
        docs = docs.toMutableList().also { it[index] = it[index].copy(filename = filename) }
        persist()
    }

    fun remove(documentId: String) {
        val updated = docs.filterNot { it.documentId == documentId }
        if (updated.size != docs.size) {
            docs = updated
            persist()
        }
    }

    companion object {
        private const val STORE_FILENAME = "word-documents.json"
    }
}

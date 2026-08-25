package ch.arcticsoft.springchat3.document

import org.springframework.stereotype.Component

/** What [DocumentMoveService.move] did, so the controller can pick the right status without re-deriving it. */
enum class DocumentMoveOutcome {
    /** Moved, or already in the requested space. */
    MOVED,

    /** No such document - already deleted, or never one. */
    NOT_FOUND,

    /** A file inside a linked Drive folder, which is owned by the sync and cannot be re-filed on its own. */
    DRIVE_FOLDER_FILE,

    /** The directory could not be relocated; nothing was changed. */
    FAILED,
}

/**
 * Moves one document into another space (2026-08-25, user's own request:
 * "Would it be possible to support drag and drop of a document from one space
 * to another"). The counterpart of [DocumentDeletionService], and deliberately
 * shaped like it - one place that knows every store holding something about a
 * document, so a second copy of the sequence cannot drift.
 *
 * **The order is much weaker than deletion's, for a reason worth stating.**
 * [DocumentIndex] and [DocumentStructureStore] have to be cleaned *before*
 * [DocumentStore] forgets a document, because they resolve `vectorstore.json`
 * and `structure.json` through [DocumentStore.documentDir]. A move never
 * forgets the document, and both files sit *inside* the directory being
 * relocated - so they travel with it and neither store is called here at all.
 * Do not "fix" that by adding a reindex: it would re-embed every chunk to
 * reproduce a file that already moved.
 *
 * The three bookkeeping stores are order-independent among themselves and each
 * is a no-op for a document that did not come from that source, so all three
 * are called unconditionally rather than the caller working out which applies.
 * [DriveLinkStore] has no equivalent call because a Drive-folder file is
 * refused outright - see [DocumentStore.moveToSpace].
 */
@Component
class DocumentMoveService(
    private val documentStore: DocumentStore,
    private val workingDocumentStore: WorkingDocumentStore,
    private val webPageStore: WebPageStore,
    private val wordDocumentStore: WordDocumentStore,
) {
    /**
     * [targetSpaceId] is nullable for symmetry with everything else in this
     * app - null means the unscoped bucket every pre-spaces document already
     * lives in - though the UI only ever moves between real spaces.
     */
    fun move(documentId: String, targetSpaceId: String?): DocumentMoveOutcome {
        val document = documentStore.get(documentId) ?: return DocumentMoveOutcome.NOT_FOUND
        if (document.driveFolderLocalId != null) return DocumentMoveOutcome.DRIVE_FOLDER_FILE
        if (!documentStore.moveToSpace(documentId, targetSpaceId)) return DocumentMoveOutcome.FAILED
        workingDocumentStore.setSpace(documentId, targetSpaceId)
        webPageStore.setSpace(documentId, targetSpaceId)
        wordDocumentStore.setSpace(documentId, targetSpaceId)
        return DocumentMoveOutcome.MOVED
    }
}

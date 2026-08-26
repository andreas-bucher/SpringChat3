package ch.arcticsoft.springchat3.document

import org.springframework.stereotype.Component

/**
 * Removes one document from every place this app keeps something about it
 * (2026-08-24, extracted from
 * [ch.arcticsoft.springchat3.web.DocumentController.delete] when deleting a
 * whole space arrived - see springchat3_space_deletion.md in project
 * memory). Two callers now need this exact sequence, and a second copy of it
 * would be a second chance to get the order wrong.
 *
 * **The order is the whole point.** [DocumentIndex] and
 * [DocumentStructureStore] resolve their own files (`vectorstore.json`,
 * `structure.json`) *through* [DocumentStore.documentDir], so they must be
 * cleaned out **before** [DocumentStore] forgets the document - afterwards
 * they cannot find what they were meant to delete, and leave orphaned files
 * behind (2026-08-23, see [DocumentStore.remove]'s own doc comment). The
 * three bookkeeping stores after them are order-independent.
 *
 * Each of [DriveLinkStore.untrackDocument], [WorkingDocumentStore.remove],
 * [WebPageStore.remove] and [WordDocumentStore.remove] is a no-op for a
 * document that did not come from that source, so all four are called
 * unconditionally rather than the caller working out which one applies.
 */
@Component
class DocumentDeletionService(
    private val documentStore: DocumentStore,
    private val documentIndex: DocumentIndex,
    private val documentStructureStore: DocumentStructureStore,
    private val driveLinkStore: DriveLinkStore,
    private val workingDocumentStore: WorkingDocumentStore,
    private val webPageStore: WebPageStore,
    private val wordDocumentStore: WordDocumentStore,
    private val userSettingsStore: ch.arcticsoft.springchat3.settings.UserSettingsStore,
) {
    /**
     * Returns false if [documentId] was not a stored document - already
     * deleted, or never one. The caller decides whether that is a `404` (the
     * per-document × button) or simply nothing to do (deleting a space).
     *
     * Never touches Google Drive itself: what goes here is this app's own
     * copy and its bookkeeping, exactly as the per-document delete has
     * always behaved.
     */
    fun delete(documentId: String): Boolean {
        if (documentStore.get(documentId) == null) return false
        documentIndex.remove(documentId)
        documentStructureStore.remove(documentId)
        documentStore.remove(documentId)
        driveLinkStore.untrackDocument(documentId)
        workingDocumentStore.remove(documentId)
        webPageStore.remove(documentId)
        wordDocumentStore.remove(documentId)
        // Housekeeping (2026-08-25): every user's per-document edit unlock
        // keys on this id, and a deleted document can never resolve again.
        userSettingsStore.forgetDocument(documentId)
        return true
    }
}

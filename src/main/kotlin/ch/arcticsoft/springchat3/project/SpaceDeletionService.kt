package ch.arcticsoft.springchat3.project

import ch.arcticsoft.springchat3.document.DocumentDeletionService
import ch.arcticsoft.springchat3.document.DocumentStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Deletes a whole space (2026-08-24, user's own request "Currently it is not
 * supported to delete a space" - see springchat3_space_deletion.md in
 * project memory).
 *
 * **Why this is a sequence and not a `deleteRecursively()`.** Since the
 * per-space storage move ([ch.arcticsoft.springchat3.document.SpaceScopedJsonStore])
 * everything a space owns lives under its one folder - its documents' bytes,
 * vector stores and structures, its `gdrive-*` folders, its chat sessions
 * and its four index files. But four components hold that state **in memory**
 * as well, and a folder that vanished underneath them would leave every one
 * of them serving entries for documents that no longer exist until the next
 * restart:
 * [DocumentStore]'s map, [ch.arcticsoft.springchat3.document.DocumentIndex]'s
 * loaded vector stores, [ch.arcticsoft.springchat3.document.DocumentStructureStore],
 * and the four per-space stores, whose lists span every space at once.
 *
 * So the folder move is the *last* step, and each document goes through the
 * very same [DocumentDeletionService] the per-document × button uses -
 * rather than a second cleanup path that could drift from it.
 *
 * **The move happens before the in-memory purge, not after.** It is the one
 * step that can fail (a rename can be refused), and it must not fail *after*
 * documents have already been forgotten. Once it succeeds, everything left
 * is bookkeeping: each store's own file deletes quietly no-op because the
 * files have already moved, which is exactly what should happen - the point
 * of the trash folder is that they still exist.
 *
 * [ch.arcticsoft.springchat3.chat.ChatHistoryStore] needs no step of its own:
 * it reads sessions from disk on every call and never caches, so the space's
 * chats disappear with its folder.
 */
@Component
class SpaceDeletionService(
    private val projectStore: ProjectStore,
    private val documentStore: DocumentStore,
    private val documentDeletionService: DocumentDeletionService,
) {
    private val log = LoggerFactory.getLogger(SpaceDeletionService::class.java)

    /**
     * Returns false if [spaceId] is not a known space, or if its folder
     * could not be moved aside - in which case nothing has been changed.
     * Access is **not** checked here; that is
     * [ch.arcticsoft.springchat3.web.ProjectController]'s job, and doing it
     * in one place keeps this a description of what deleting means rather
     * than of who may do it.
     */
    fun delete(spaceId: String): Boolean {
        // Snapshotted before anything moves: documentStore.list() is how the
        // space's documents are found at all, and it is about to lose them.
        val documentIds = documentStore.list().filter { it.spaceId == spaceId }.map { it.documentId }
        if (!projectStore.moveToTrash(spaceId)) return false
        documentIds.forEach { documentDeletionService.delete(it) }
        log.info("Deleted space {} and its {} documents", spaceId, documentIds.size)
        return true
    }
}

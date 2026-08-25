package ch.arcticsoft.springchat3.document

import ch.arcticsoft.springchat3.project.ProjectStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/**
 * Splits one of this app's "list of things, keyed by nothing in particular"
 * JSON stores across the space folders, one file per space, instead of
 * keeping every space's entries in a single file at the data root
 * (2026-08-24, user's own question: "Would it not make more sense to store
 * word-documents.json and web-pages.json in the spaces folders? they are
 * currently in the data folder").
 *
 * The four stores that use it ([WordDocumentStore], [WebPageStore],
 * [WorkingDocumentStore], [DriveLinkStore]) were the last cross-space state
 * left in the data root: [DocumentStore] already writes a document's own
 * files to `[dataDir]/spaces/<spaceId>/<documentId>/`, and
 * [ch.arcticsoft.springchat3.chat.ChatHistoryStore] already writes sessions
 * to `[dataDir]/spaces/<spaceId>/sessions/`, so a space folder held an
 * uploaded `.docx`'s *bytes* while the entry naming it lived somewhere else
 * entirely. Now `[dataDir]/spaces/<spaceId>/<filename>` holds that space's
 * entries, and a space folder is self-contained: whenever
 * [ch.arcticsoft.springchat3.web.ProjectController] grows a delete (it has
 * only list/create today), deleting a space becomes one recursive directory
 * delete rather than a sweep through four stores that each have to filter by
 * `spaceId` and any one of which could be forgotten.
 *
 * **Entries with no space** (`spaceId == null` - uploaded or linked with no
 * project active) go to `[dataDir]/<name>-unassigned.json`, the same "one
 * bucket outside the spaces tree" treatment
 * [ch.arcticsoft.springchat3.chat.ChatHistoryStore] gives its own
 * unassigned sessions via `chat-history-unassigned/`. The deliberately
 * *different* filename is what keeps the old root-level file from being
 * silently re-read as the unassigned bucket: **there is no migration** (the
 * user's own choice when this was agreed), so a pre-existing
 * `[dataDir]/word-documents.json` etc. is simply ignored from here on and
 * can be deleted by hand.
 *
 * The callers keep their whole-list in-memory shape - each still loads once
 * at startup into a `@Volatile var` and still looks entries up by
 * `documentId`/`url`/`driveFileId` alone, with no `spaceId` in hand (which
 * matters: [ch.arcticsoft.springchat3.web.DocumentController.delete] calls
 * every store's `remove(id)` knowing only the document id). Only the two
 * ends move: [load] concatenates every space's file plus the unassigned one,
 * [persist] groups the list back up by space and rewrites each file. That
 * keeps all four public APIs byte-identical and leaves every call site
 * untouched.
 *
 * Two consequences worth knowing:
 * - **[getAll]-style ordering is now grouped by space**, where it used to be
 *   one global insertion order. Insertion order still holds *within* a
 *   space, and index.html renders each space's resources from its own block
 *   (filtered by `spaceId`), so nothing on screen changes.
 * - **Space membership is read back from the filesystem, not from
 *   [ProjectStore.list].** [persist] writes into `spaceDir(id)` for whatever
 *   id an entry carries, whether or not it's a currently-known project, so
 *   [load] enumerates directories the same way - otherwise an entry could be
 *   written to a file that the next startup then refuses to look at.
 */
@Component
class SpaceScopedJsonStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
    private val projectStore: ProjectStore,
) {
    private val log = LoggerFactory.getLogger(SpaceScopedJsonStore::class.java)
    private val objectMapper = jacksonObjectMapper()

    /**
     * Every entry currently on disk for [filename], across every space plus
     * the unassigned bucket. A corrupt file logs a warning and contributes
     * nothing rather than failing application startup - same contract each
     * caller's own `loadPersisted` had, just per space now, so one unreadable
     * file costs one space's entries instead of every space's.
     */
    fun <T : Any> load(filename: String, type: Class<T>): List<T> =
        storageKeys().flatMap { readList(fileFor(it, filename), type) }

    /**
     * Rewrites [filename] everywhere it lives, grouping [items] by
     * [spaceIdOf]. A file whose space no longer has any entry is deleted
     * rather than left holding its old contents - without that, removing the
     * last entry in a space would leave a file that the next [load] happily
     * reads back.
     */
    fun <T : Any> persist(filename: String, items: List<T>, spaceIdOf: (T) -> String?) {
        val grouped = items.groupBy(spaceIdOf)
        grouped.forEach { (spaceId, group) -> write(fileFor(spaceId, filename), group) }
        // After the writes, so a space folder created by one of them is seen here and kept.
        storageKeys().filterNot { grouped.containsKey(it) }.forEach { key ->
            val file = fileFor(key, filename)
            if (file.exists() && !file.delete()) {
                log.warn("Could not delete now-empty {}", file)
            }
        }
    }

    /** Null (the unassigned bucket) plus one key per space folder on disk. */
    private fun storageKeys(): List<String?> = listOf<String?>(null) + projectStore.spaceDirs().map { it.name }

    private fun fileFor(spaceId: String?, filename: String): File =
        if (spaceId == null) File(dataDir, unassignedName(filename)) else File(projectStore.spaceDir(spaceId), filename)

    private fun unassignedName(filename: String) = filename.removeSuffix(".json") + "-unassigned.json"

    private fun <T : Any> readList(file: File, type: Class<T>): List<T> {
        if (!file.exists()) return emptyList()
        val listType = objectMapper.typeFactory.constructCollectionType(List::class.java, type)
        return try {
            objectMapper.readValue(file, listType)
        } catch (e: Exception) {
            log.warn("Could not load persisted entries from {} - skipping that file", file, e)
            emptyList()
        }
    }

    private fun <T : Any> write(file: File, items: List<T>) {
        try {
            file.parentFile?.mkdirs()
            objectMapper.writeValue(file, items)
        } catch (e: Exception) {
            log.warn("Could not persist entries to {}", file, e)
        }
    }
}

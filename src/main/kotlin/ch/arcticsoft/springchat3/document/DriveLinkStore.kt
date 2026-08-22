package ch.arcticsoft.springchat3.document

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/**
 * One PDF this app has already pulled in from one of the linked Google Drive
 * folders and ingested through the same pipeline a direct upload goes
 * through (see [ch.arcticsoft.springchat3.web.DriveController]'s own doc
 * comment) - [documentId] is the very same id [DocumentStore]/[DocumentIndex]
 * know it by, so deleting it via the existing `DELETE /documents/{id}` (see
 * [ch.arcticsoft.springchat3.web.DocumentController.delete]) works
 * identically whether a document came from an upload or from Drive.
 * [modifiedTime]/[md5Checksum] are Drive's own change-detection fields
 * (from the `files.list` response) - a later sync re-downloads/re-ingests a
 * file only if its [md5Checksum] has changed, otherwise it's left alone.
 */
data class DriveSyncedFile(
    val driveFileId: String,
    val documentId: String,
    val filename: String,
    val modifiedTime: String?,
    val md5Checksum: String?,
)

/**
 * One linked Google Drive folder (2026-08-22, user's own request
 * "additionally to upload Documents, it should be possible to link to a
 * Google Drive Folder" - see springchat3_google_drive.md in project memory
 * for the full design). Originally "one folder at a time" (v1) - changed
 * the same day to allow more than one simultaneously linked folder (v2, see
 * [DriveLinkStore]'s own doc comment for why) - [folderId] is unique among
 * whatever [DriveLinkStore] currently holds, but there is no longer a single
 * "the" link. [lastSyncedAt] is null only in the brief window before this
 * particular folder's very first sync (kicked off automatically right after
 * linking - see [ch.arcticsoft.springchat3.web.DriveController.link])
 * actually completes.
 */
data class DriveLink(
    val folderId: String,
    val folderName: String,
    val linkedAt: Long,
    val lastSyncedAt: Long? = null,
    val files: List<DriveSyncedFile> = emptyList(),
)

/**
 * Persists every currently linked [DriveLink] to `[data-dir]/drive-link.json`
 * (filename kept as-is across the v1→v2 format change below - it's still
 * "the Drive link state", just a list now) - same single-shared-file JSON
 * persistence pattern as [ch.arcticsoft.springchat3.settings.AppSettingsStore],
 * just one file for every linked folder rather than one per document. An
 * empty list (no file on disk, or the last folder [unlink]d) means "nothing
 * linked", loaded eagerly at construction like [AppSettingsStore] - there's
 * only ever this one small list to load.
 *
 * **v1→v2 (2026-08-22, same day): more than one folder can be linked at
 * once.** User's own bug report: "when linking a new Google Drive folder,
 * the previously uploaded files are lost. The folder should stay. A 2nd
 * Folder should be added which then contains the newly uploaded files." v1's
 * [link] replaced whatever single [DriveLink] was stored, which is exactly
 * what silently dropped the previous folder's bookkeeping - the previous
 * folder's already-ingested *documents* were never actually deleted (nothing
 * here ever touches [DocumentStore]/[DocumentIndex] directly, see below),
 * but this store stopped tracking them, so a later "Sync now" on the old
 * folder was impossible (nothing left pointed at it) and the old folder's
 * card simply vanished from the UI. Storage is now `List<DriveLink>` keyed
 * by [DriveLink.folderId] instead of a single nullable value, and every
 * method below is scoped to one [folderId] rather than "the" link.
 * [loadPersisted] transparently migrates an old single-object
 * `drive-link.json` (from before this change) into a one-element list the
 * first time it's read, so an existing linked folder and its synced-files
 * bookkeeping survive the upgrade rather than silently disappearing again -
 * the exact failure mode this change exists to fix.
 *
 * Deliberately doesn't touch [DocumentStore]/[DocumentIndex] itself - it
 * only tracks *which* Drive files are already ingested, under which
 * [DriveLink.folderId] and [DriveSyncedFile.documentId]; actually
 * ingesting/removing a document is
 * [ch.arcticsoft.springchat3.web.DriveController]'s job. [untrackDocument]
 * exists solely so [ch.arcticsoft.springchat3.web.DocumentController.delete]
 * can keep this store's bookkeeping honest when a Drive-sourced document is
 * deleted the same way an uploaded one is (the generic per-document ×
 * button) - without it, a later "Sync now" would see that Drive file as
 * "already synced" and never re-offer a file the user just removed from the
 * app's index, even though (per the UI's own explanation) deleting only
 * removes it from the app, not from Drive.
 *
 * Write-through, same simple approach as [AppSettingsStore]/[DocumentStore]:
 * every mutation persists immediately, no batching. A load failure at
 * startup (corrupt file, and not the legacy single-object shape either) logs
 * a warning and starts with nothing linked rather than failing application
 * startup.
 */
@Component
class DriveLinkStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
) {
    private val log = LoggerFactory.getLogger(DriveLinkStore::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var links: List<DriveLink> = loadPersisted()

    private fun linkFile() = File(dataDir, "drive-link.json")

    private fun loadPersisted(): List<DriveLink> {
        val file = linkFile()
        if (!file.exists()) return emptyList()
        return try {
            objectMapper.readValue<List<DriveLink>>(file)
        } catch (e: Exception) {
            // Not a JSON array - probably the pre-2026-08-22 single-object
            // format (one bare DriveLink, from before more than one folder
            // could be linked). Try reading it that way before giving up, so
            // upgrading this app doesn't silently drop an already-linked
            // folder's sync bookkeeping.
            try {
                val legacy = objectMapper.readValue<DriveLink>(file)
                log.info(
                    "Migrating pre-multi-folder Google Drive link ('{}') to the new list format",
                    legacy.folderName,
                )
                listOf(legacy)
            } catch (legacyError: Exception) {
                log.warn("Could not load persisted Google Drive links from {} - starting with none linked", file, e)
                emptyList()
            }
        }
    }

    private fun persist() {
        try {
            val file = linkFile()
            file.parentFile?.mkdirs()
            objectMapper.writeValue(file, links)
        } catch (e: Exception) {
            log.warn("Could not persist Google Drive links to {}", linkFile(), e)
        }
    }

    /** Every currently linked folder. */
    fun getAll(): List<DriveLink> = links

    /** One specific linked folder, or null if [folderId] isn't (or is no longer) linked. */
    fun get(folderId: String): DriveLink? = links.find { it.folderId == folderId }

    /**
     * Links [folderId]/[folderName] as an ADDITIONAL folder alongside
     * whatever else is already linked (2026-08-22 - see this class's own
     * doc comment for why this used to replace the single existing link
     * instead). Idempotent if [folderId] is already linked - returns the
     * existing entry unchanged rather than duplicating it or resetting its
     * already-synced [DriveLink.files] back to empty; the Picker flow
     * shouldn't normally offer an already-linked folder again, but this
     * keeps a double-click or a stale-UI race harmless instead of silently
     * wiping real sync state. Does NOT itself ingest any files - see
     * [ch.arcticsoft.springchat3.web.DriveController.link], which calls this
     * and then immediately syncs just this folder.
     */
    fun link(folderId: String, folderName: String): DriveLink {
        get(folderId)?.let { return it }
        val fresh = DriveLink(folderId = folderId, folderName = folderName, linkedAt = System.currentTimeMillis())
        links = links + fresh
        persist()
        return fresh
    }

    /**
     * Unlinks just [folderId]. Every other linked folder - and its own
     * already-ingested documents - is left untouched, same as before
     * (unlinking is a bookkeeping change, not a bulk delete of
     * previously-synced documents), just scoped to one folder now that more
     * than one can be linked at once. No-op if [folderId] isn't currently
     * linked.
     */
    fun unlink(folderId: String) {
        val updated = links.filterNot { it.folderId == folderId }
        if (updated.size != links.size) {
            links = updated
            persist()
        }
    }

    /**
     * Replaces [folderId]'s tracked file list and [DriveLink.lastSyncedAt]
     * after a sync pass completes. No-op if [folderId] isn't currently
     * linked (e.g. unlinked concurrently with a sync in flight for it).
     */
    fun replaceFiles(folderId: String, files: List<DriveSyncedFile>, syncedAt: Long) {
        if (get(folderId) == null) return
        links = links.map { if (it.folderId == folderId) it.copy(files = files, lastSyncedAt = syncedAt) else it }
        persist()
    }

    /**
     * Adds or replaces just [file] within [folderId]'s tracked list, leaving
     * every other file already tracked there untouched - the incremental
     * counterpart to [replaceFiles] (2026-08-22, "let the file-by-file sync
     * continue in the background" - user's own follow-up after a real
     * embed-call interruption during a folder's *inline* first sync lost
     * every file's progress for that request, since nothing was persisted
     * until the whole batch finished - see springchat3_google_drive.md in
     * project memory). [ch.arcticsoft.springchat3.web.DriveController.performSync]
     * now calls this once per file, right as each one finishes ingesting,
     * instead of only calling [replaceFiles] once at the very end - so an
     * interruption partway through a background sync only loses whatever
     * hadn't completed *yet*, not the whole pass. [replaceFiles] is still
     * called once at the end of a sync pass to prune any file no longer
     * present in the current Drive listing (something no per-file call can
     * do on its own, since it never sees the full picture). No-op if
     * [folderId] isn't currently linked, same guard [replaceFiles] has.
     */
    fun upsertFile(folderId: String, file: DriveSyncedFile) {
        if (get(folderId) == null) return
        links = links.map { link ->
            if (link.folderId == folderId) {
                link.copy(files = link.files.filterNot { it.driveFileId == file.driveFileId } + file)
            } else {
                link
            }
        }
        persist()
    }

    /**
     * Drops [documentId]'s entry from whichever linked folder currently
     * tracks it, if any - see this class's own doc comment for why. Checks
     * every linked folder rather than just one, since with more than one
     * folder now linkable, [ch.arcticsoft.springchat3.web.DocumentController.delete]
     * (the only caller) has no way to know - and shouldn't need to know -
     * which folder a given [documentId] originally came from. No-op if no
     * linked folder tracks [documentId].
     */
    fun untrackDocument(documentId: String) {
        var changed = false
        val updated = links.map { link ->
            val remaining = link.files.filterNot { it.documentId == documentId }
            if (remaining.size != link.files.size) {
                changed = true
                link.copy(files = remaining)
            } else {
                link
            }
        }
        if (changed) {
            links = updated
            persist()
        }
    }
}

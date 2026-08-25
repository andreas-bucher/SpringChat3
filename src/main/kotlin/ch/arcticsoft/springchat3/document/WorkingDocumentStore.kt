package ch.arcticsoft.springchat3.document

import org.springframework.stereotype.Component

/**
 * One individually-linked Google Doc (2026-08-22, "Working Documents" -
 * user's own request "Enable to link a Google Doc from Google Drive" - see
 * springchat3_working_documents.md in project memory), distinct from a whole
 * synced [DriveLink] folder of PDFs: this is a single native Google Doc the
 * user picked directly via the Google Picker, kept live-linked with a manual
 * resync (user's own choice - "link + manual resync", not a one-time
 * import) so later edits made in the actual Google Doc can be pulled in on
 * demand.
 *
 * [documentId] identifies this doc in [DocumentStore]/[DocumentIndex]/
 * [DocumentStructureStore] exactly like any other document - a Google Doc
 * has no raw bytes of its own to store/index directly, so
 * [ch.arcticsoft.springchat3.web.DriveController.linkDoc]/[ch.arcticsoft.springchat3.web.DriveController.syncDoc]
 * export it as a PDF first (Drive API v3 `files/{id}/export?mimeType=application/pdf`,
 * not the `alt=media` download a binary file like an uploaded/synced PDF
 * uses) and ingest that exported PDF through the exact same pipeline any
 * other PDF goes through - see [ch.arcticsoft.springchat3.web.DriveController]'s
 * own doc comment. [documentId] changes on every resync (ingesting always
 * produces a fresh id via [DocumentStore.store] - the old entry is removed
 * first, same as a changed Drive-folder PDF being re-ingested), which is
 * exactly why this is keyed by [driveFileId] (Drive's own, stable file id)
 * for lookups that need to survive a resync, not by [documentId] itself.
 *
 * No `modifiedTime`/checksum field the way [DriveSyncedFile] has one for
 * folder-synced PDFs: resync here is always a manual, single-document
 * click (see this class's own doc comment above), not an unattended
 * reconciliation pass deciding what changed - a deliberate click already
 * means "pull the latest version," so there's nothing to diff against
 * first, and Drive's own change-detection fields are less reliable for a
 * native Google Doc than the `md5Checksum` a binary PDF gets.
 *
 * [spaceId] (2026-08-23, user's own request "link a working document...
 * then save the files in the project folder of the active project" - see
 * springchat3_projects_panel.md in project memory) is the project that was
 * active when this Doc was first linked, or null for none - carried forward
 * on every resync (see [ch.arcticsoft.springchat3.web.DriveController.syncDoc])
 * rather than re-read from whatever project happens to be active at resync
 * time, same "fixed at link time" reasoning [DriveLink.spaceId] has.
 */
data class LinkedGoogleDoc(
    val driveFileId: String,
    val documentId: String,
    val filename: String,
    val linkedAt: Long,
    val lastSyncedAt: Long,
    val spaceId: String? = null,
)

/**
 * Persists every currently linked [LinkedGoogleDoc] to
 * `working-documents.json` **inside each space's own folder** (2026-08-24) -
 * see [SpaceScopedJsonStore] for the layout, the unassigned bucket and why
 * there's no migration off the old single
 * `[data-dir]/working-documents.json`. Same write-through, load-once JSON
 * pattern [DriveLinkStore] uses for its own (much larger) `List<DriveLink>`,
 * just for individually-linked Google Docs instead of whole synced folders.
 * A separate store rather than folding into [DriveLinkStore] itself: a
 * linked Google Doc isn't inside a folder at all (the mockup/proposal for
 * this feature - see springchat3_working_documents.md in project memory -
 * deliberately kept "Working Documents" as its own side-panel section,
 * sibling to "Google Drive", not nested under it), and [DriveLink]'s own
 * shape (`folderId`/`folderName`/`files: List<DriveSyncedFile>`) has no
 * natural place for a single standalone file with no folder of its own.
 *
 * Write-through, no batching, same simple approach as every other store in
 * this app ([DocumentStore], [DriveLinkStore], [AppSettingsStore]) - a load
 * failure at startup (corrupt file) logs a warning and starts with nothing
 * linked rather than failing application startup.
 */
@Component
class WorkingDocumentStore(
    private val spaceScopedStore: SpaceScopedJsonStore,
) {
    @Volatile
    private var docs: List<LinkedGoogleDoc> = spaceScopedStore.load(STORE_FILENAME, LinkedGoogleDoc::class.java)

    private fun persist() = spaceScopedStore.persist(STORE_FILENAME, docs) { it.spaceId }

    /** Every currently linked Google Doc. */
    fun getAll(): List<LinkedGoogleDoc> = docs

    /** One linked Google Doc by its current [documentId], or null if [documentId] isn't (or is no longer) one. */
    fun get(documentId: String): LinkedGoogleDoc? = docs.find { it.documentId == documentId }

    /**
     * One linked Google Doc by Drive's own stable [driveFileId] - unlike
     * [documentId] (see this class's own doc comment), this survives a
     * resync, so [ch.arcticsoft.springchat3.web.DriveController.linkDoc]
     * uses it to recognize "this Drive file is already linked" (treating a
     * repeat pick as a resync rather than a duplicate entry) the same way
     * [DriveLinkStore.link]'s own idempotency check does for a folder.
     */
    fun getByDriveFileId(driveFileId: String): LinkedGoogleDoc? = docs.find { it.driveFileId == driveFileId }

    /**
     * Adds [doc] as a fresh link, or - if [LinkedGoogleDoc.driveFileId]
     * already has an entry (a resync, which always produces a new
     * [LinkedGoogleDoc.documentId] - see this class's own doc comment) -
     * replaces that entry with [doc] rather than leaving the stale one
     * alongside it. The one write path both [ch.arcticsoft.springchat3.web.DriveController.linkDoc]
     * (a brand new link) and [ch.arcticsoft.springchat3.web.DriveController.syncDoc]
     * (a resync of an existing one) call.
     */
    fun upsert(doc: LinkedGoogleDoc) {
        docs = docs.filterNot { it.driveFileId == doc.driveFileId } + doc
        persist()
    }

    /**
     * Drops [documentId]'s link entirely - called by
     * [ch.arcticsoft.springchat3.web.DocumentController.delete] alongside
     * [DriveLinkStore.untrackDocument] whenever a document's × button is
     * used, a no-op for a [documentId] that was never a linked Google Doc.
     * Unlike unlinking a Google Drive *folder* (which deliberately leaves
     * its already-ingested documents alone - see [DriveLinkStore.unlink]'s
     * own doc comment), there's no lighter-weight "stop tracking but keep
     * the document" case for a single linked Doc: its whole reason for
     * being ingested at all was this link, so the existing per-document ×
     * delete (which already removes the ingested [DocumentStore]/
     * [DocumentIndex]/[DocumentStructureStore] entries) is the only removal
     * path - this method just keeps that same bookkeeping honest,
     * mirroring [DriveLinkStore.untrackDocument]'s own role for a
     * folder-sourced document.
     */
    /**
     * Re-files [documentId] under [spaceId], a no-op for an id that is not a
     * linked Google Doc (2026-08-25, moving a document between spaces -
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

    fun remove(documentId: String) {
        val updated = docs.filterNot { it.documentId == documentId }
        if (updated.size != docs.size) {
            docs = updated
            persist()
        }
    }

    companion object {
        private const val STORE_FILENAME = "working-documents.json"
    }
}

package ch.arcticsoft.springchat3.document

import ch.arcticsoft.springchat3.project.ProjectStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * One uploaded document's extracted plain text, plus its original filename.
 * Through document-Q&A "Phase 1" (see springchat3_document_qa.md in project
 * memory), [text] was what [ch.arcticsoft.springchat3.agent.ChatAgent.answer]
 * folded (size-capped) directly into its prompt. Since "Phase 2" (2026-08-22,
 * chunking + embeddings + vector-store retrieval - see
 * [ch.arcticsoft.springchat3.document.DocumentIndex]), [text] is
 * display-only: it backs [DocumentStore.list]'s character-count metadata for
 * the UI's side panel, nothing else - the actual content
 * [ch.arcticsoft.springchat3.agent.ChatAgent.answer] works from now comes
 * from [DocumentIndex.search]'s per-question retrieval against the chunks
 * [DocumentIndex.index] stored separately at upload time, not from this
 * field. [uploadedAt] (added with the per-document storage layout below,
 * still 2026-08-22) exists solely so [DocumentStore.list] can keep showing
 * documents in upload order after a restart, now that documents are no
 * longer kept in one order-preserving in-memory map loaded from one file -
 * see [DocumentStore.loadPersisted]. A plain Kotlin data class so Jackson
 * (via `jackson-module-kotlin`, already a dependency) can serialize/
 * deserialize it directly for [DocumentStore]'s persistence file.
 *
 * [projectId] (2026-08-23, user's own request "when uploading a file or link
 * a google drive folder or link a working document then save the files in
 * the project folder of the active project" - see
 * springchat3_projects_panel.md in project memory) is null for a document
 * uploaded/synced with no active project - the pre-existing, still-supported
 * case - or the id of the project that was active at the time it was
 * ingested otherwise. Nullable with a default so a pre-2026-08-23
 * `document.json` with no `projectId` key at all still deserializes cleanly
 * (a missing key on a nullable Kotlin constructor parameter binds to null via
 * plain Jackson behavior, not the separate/newer Kotlin-default-parameter
 * mechanism this project has been deliberately cautious about elsewhere -
 * see [ch.arcticsoft.springchat3.web.CreateProjectRequest]'s own doc
 * comment).
 *
 * [driveFolderLocalId] (2026-08-23, user's own request "When sync a google
 * drive folder. add a new folder with the name 'gdrive-'+6 digits. save the
 * files in that folder" - see springchat3_projects_panel.md in project
 * memory) is set only for a document synced in from a linked Google Drive
 * folder (see [ch.arcticsoft.springchat3.document.DriveLink.driveFolderLocalId]) -
 * null for a directly-uploaded document, or one from a linked Working
 * Document, neither of which this feature touches. Same nullable-with-
 * default reasoning as [projectId] above.
 */
data class ExtractedDocument(
    val filename: String,
    val text: String,
    val uploadedAt: Long,
    val projectId: String? = null,
    val driveFolderLocalId: String? = null,
    val rawFilename: String = DEFAULT_RAW_FILENAME,
)

/**
 * What [ExtractedDocument.rawFilename] defaults to - the name every raw
 * document byte-blob was unconditionally stored under before uploaded Word
 * documents existed (2026-08-23, see
 * [ch.arcticsoft.springchat3.web.WordDocumentController]), when a document's
 * raw bytes were always a PDF. Kept as the default so every already-persisted
 * `document.json` (none of which has this field) keeps resolving to the file
 * that's actually on disk next to it, with no migration pass.
 */
const val DEFAULT_RAW_FILENAME = "document.pdf"

/**
 * One stored document's identity/metadata for the UI's document list (see
 * [DocumentStore.list]) - deliberately without [ExtractedDocument.text],
 * which can be tens of thousands of characters and never needs to reach the
 * browser (see [ch.arcticsoft.springchat3.agent.ChatRequest.documentId]'s
 * doc comment for why the extracted text itself stays server-side).
 *
 * [projectId] (2026-08-23, user's own request "The right panel shall display
 * the project resources of the selected project of the left panel" - see
 * springchat3_projects_panel.md in project memory) mirrors
 * [ExtractedDocument.projectId] - null for a document with no active project
 * at ingest time. index.html filters its document/folder/working-doc lists
 * against `activeProjectId` using exactly this field, so the right panel
 * shows only the selected project's own resources.
 */
data class DocumentSummary(val documentId: String, val filename: String, val characterCount: Int, val projectId: String? = null)

/**
 * Store for documents uploaded via
 * [ch.arcticsoft.springchat3.web.DocumentController], keyed by a generated
 * [documentId] the frontend then includes on subsequent
 * [ch.arcticsoft.springchat3.agent.ChatRequest]s so
 * [ch.arcticsoft.springchat3.agent.ChatAgent.answer] can retrieve the right
 * document's relevant chunks (via [ch.arcticsoft.springchat3.document.DocumentIndex])
 * - see springchat3_document_qa.md in project memory for the full design.
 *
 * **Per-document storage layout since 2026-08-22** (see
 * springchat3_document_qa.md in project memory - this replaces the earlier,
 * same-day design of one shared `documents.json` holding every document):
 * each document gets its own `document.json` in its own directory (see
 * [documentDir]) - one file per document rather than one shared file, so a
 * mutation/load failure is bounded to one document, not every stored
 * document at once (see [loadPersisted]'s per-file try/catch). [DocumentIndex]'s
 * vector-store persistence and [DocumentStructureStore]'s outline
 * persistence both delegate to this class's own [documentDir] rather than
 * duplicating the placement logic (see [documentDir]'s own doc comment).
 *
 * **A document's directory can be in any of up to four places, composed from
 * two independent, orthogonal choices (2026-08-23):** whether a project was
 * active at ingest time ([ExtractedDocument.projectId], own doc comment) and
 * whether the document came from a linked Google Drive folder
 * ([ExtractedDocument.driveFolderLocalId], own doc comment) - `[dataDir]/<documentId>/`
 * (neither), `[dataDir]/gdrive-<driveFolderLocalId>/<documentId>/`
 * (Drive-sourced, no active project), `[dataDir]/projects/<projectId>/<documentId>/`
 * (uploaded/linked with a project active), or
 * `[dataDir]/projects/<projectId>/gdrive-<driveFolderLocalId>/<documentId>/`
 * (Drive-sourced, with a project active at link time) - [documentDir]
 * resolves which, and [loadPersisted] walks every layout at startup to
 * discover every document regardless of which one it's in.
 *
 * **Also holds the original PDF bytes, same directory, since 2026-08-22**
 * (user's own idea "would it be possible to enable the files to be
 * displayed on another browser tab?" - see springchat3_document_qa.md in
 * project memory for the "open in a new tab" feature this enabled): each
 * document's raw bytes live alongside its `document.json`, as a sibling
 * `document.pdf` - see [getBytes]. Kept as a plain file rather than folded
 * into [ExtractedDocument] itself (e.g. as a base64 field) so [documentFile]
 * stays small, JSON-only metadata, and the raw bytes can be read/streamed
 * back independently without ever having to parse-then-decode them out of a
 * JSON document first. **A document stored before this change has no
 * `document.pdf`** - [getBytes] simply returns null for one of those rather
 * than treating it as an error; the side panel's "open in a new tab" button
 * surfaces that as an ordinary 404, not a special case this store needs to
 * know about.
 *
 * **Not migrated from the old single-`documents.json` layout**: any
 * document uploaded before this change is simply no longer found (the old
 * file is orphaned, not deleted) and needs re-uploading. Deliberately not
 * worth writing one-time migration code for, at this app's real scale so
 * far (one test upload during development).
 *
 * A `LinkedHashMap` wrapped in [Collections.synchronizedMap] - not a plain
 * `ConcurrentHashMap` - since the side-panel document list (see [list]) is
 * expected to show uploads in the order they were added, which
 * `ConcurrentHashMap` doesn't guarantee but `LinkedHashMap` does (directory
 * listing order at startup isn't guaranteed either, which is why
 * [loadPersisted] explicitly sorts by [ExtractedDocument.uploadedAt] before
 * building this map); `synchronizedMap` gets back the thread-safety a
 * `ConcurrentHashMap` gave for free, at the cost of manual `synchronized`
 * blocks around any iteration/whole-map read (see [list]) - single-user
 * local app, so the extra locking is irrelevant in practice.
 *
 * Write-through, same simple approach as before: [store]/[remove] persist
 * immediately, no batching, no atomic temp-file-plus-rename (a crash
 * mid-write could in theory corrupt one document's file - acceptable risk
 * here, revisit if it ever actually happens, and now bounded to that one
 * document rather than the whole store). A load failure for one document's
 * file logs a warning and skips just that document rather than failing
 * application startup.
 */
@Component
class DocumentStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
    private val projectStore: ProjectStore,
) {
    private val log = LoggerFactory.getLogger(DocumentStore::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val documents = Collections.synchronizedMap(loadPersisted())

    /**
     * The container a document's own directory sits directly inside:
     * the active project's own folder if [projectId] is set, otherwise the
     * flat data directory - then, if [driveFolderLocalId] is also set
     * (2026-08-23, see [ExtractedDocument.driveFolderLocalId]'s own doc
     * comment), one further `gdrive-<driveFolderLocalId>` level inside that.
     * The two choices are independent, hence the up-to-four-layouts list in
     * this class's own doc comment.
     */
    private fun documentContainerFor(projectId: String?, driveFolderLocalId: String?): File {
        val base = if (projectId != null) projectStore.projectDir(projectId) else File(dataDir)
        return if (driveFolderLocalId != null) File(base, "gdrive-$driveFolderLocalId") else base
    }

    private fun documentDirFor(documentId: String, projectId: String?, driveFolderLocalId: String?): File =
        File(documentContainerFor(projectId, driveFolderLocalId), documentId)

    /**
     * [documentId]'s own on-disk directory - null only if [documentId] isn't
     * (or is no longer) a known document. Public (2026-08-23, see this
     * class's own doc comment) so [DocumentIndex]/[DocumentStructureStore]
     * can resolve their own per-document files (`vectorstore.json`/
     * `structure.json`) through this single source of truth instead of each
     * independently guessing whether a document lives under a project
     * folder and/or a `gdrive-<id>` folder - both fall back to the flat,
     * unscoped path if this returns null (an unrecognized id), same
     * defensive behavior they had before this feature existed.
     */
    fun documentDir(documentId: String): File? =
        documents[documentId]?.let { documentDirFor(documentId, it.projectId, it.driveFolderLocalId) }

    private fun documentFile(documentId: String, projectId: String?, driveFolderLocalId: String?) =
        File(documentDirFor(documentId, projectId, driveFolderLocalId), "document.json")

    private fun documentRawFile(documentId: String, projectId: String?, driveFolderLocalId: String?, rawFilename: String) =
        File(documentDirFor(documentId, projectId, driveFolderLocalId), rawFilename)

    private fun loadPersisted(): LinkedHashMap<String, ExtractedDocument> {
        val root = File(dataDir)
        root.mkdirs()
        val loaded = mutableListOf<Pair<String, ExtractedDocument>>()
        fun loadFrom(dir: File) {
            val docFile = File(dir, "document.json")
            if (docFile.exists()) {
                try {
                    loaded += dir.name to objectMapper.readValue<ExtractedDocument>(docFile)
                } catch (e: Exception) {
                    log.warn("Could not load persisted document from {} - skipping", docFile, e)
                }
            }
        }
        // One "documents container" (the flat dataDir root, or one project's
        // own folder) can hold plain document directories directly, and/or -
        // since 2026-08-23, see this class's own doc comment - one extra
        // level of gdrive-<id> subdirectories (one per Drive-folder link),
        // each in turn holding that folder's own document directories. A
        // child directory's name starting with "gdrive-" is how it's told
        // apart from a plain document directory at the same nesting level -
        // a document directory is always named with a random UUID (see
        // [store]), which never starts with "gdrive-".
        fun loadContainer(container: File, exclude: Set<String> = emptySet()) {
            container.listFiles { file -> file.isDirectory && file.name !in exclude }?.forEach { child ->
                if (child.name.startsWith("gdrive-")) {
                    child.listFiles { file -> file.isDirectory }?.forEach(::loadFrom)
                } else {
                    loadFrom(child)
                }
            }
        }
        // Flat, unscoped documents (no active project at ingest time) - the
        // "projects" subdirectory itself holds per-project data, not a
        // document/gdrive container, so it's explicitly excluded here rather
        // than walked as if it were one.
        loadContainer(root, exclude = setOf("projects"))
        // Documents nested one level deeper under each project's own folder.
        File(root, "projects").listFiles { file -> file.isDirectory }?.forEach { projectDir -> loadContainer(projectDir) }
        loaded.sortBy { (_, doc) -> doc.uploadedAt }
        val map = LinkedHashMap<String, ExtractedDocument>()
        loaded.forEach { (id, doc) -> map[id] = doc }
        return map
    }

    private fun persist(documentId: String, document: ExtractedDocument) {
        try {
            val dir = documentDirFor(documentId, document.projectId, document.driveFolderLocalId)
            dir.mkdirs()
            objectMapper.writeValue(documentFile(documentId, document.projectId, document.driveFolderLocalId), document)
        } catch (e: Exception) {
            log.warn("Could not persist document {} to {}", documentId, documentFile(documentId, document.projectId, document.driveFolderLocalId), e)
        }
    }

    private fun persistBytes(documentId: String, projectId: String?, driveFolderLocalId: String?, rawFilename: String, bytes: ByteArray) {
        try {
            val dir = documentDirFor(documentId, projectId, driveFolderLocalId)
            dir.mkdirs()
            documentRawFile(documentId, projectId, driveFolderLocalId, rawFilename).writeBytes(bytes)
        } catch (e: Exception) {
            log.warn("Could not persist raw bytes for document {} to {}", documentId, documentRawFile(documentId, projectId, driveFolderLocalId, rawFilename), e)
        }
    }

    /**
     * Stores [text] extracted from [filename] plus [bytes], the document's
     * original PDF content (2026-08-22, see this class's own doc comment) -
     * returns a fresh id to look either one up by later ([get]/[getBytes]).
     * [projectId] (2026-08-23, see [ExtractedDocument.projectId]'s own doc
     * comment) places this document's whole directory inside that project's
     * own folder instead of the flat, unscoped default when non-null;
     * [driveFolderLocalId] (2026-08-23, see [ExtractedDocument.driveFolderLocalId]'s
     * own doc comment) additionally nests it inside a `gdrive-<id>` folder
     * when non-null - the two compose independently (see this class's own
     * doc comment for all four resulting layouts). A failure persisting
     * [bytes] is logged and otherwise swallowed, same as [persist] itself -
     * this store already tolerates a failed metadata write without failing
     * the whole upload/sync, so a failed bytes write gets the same treatment
     * rather than being held to a stricter standard; the practical effect is
     * just that [getBytes] later returns null for this [documentId], same as
     * any other pre-existing document that never had its bytes stored.
     *
     * [bytes] is nullable (2026-08-23, "Web Pages" - see
     * springchat3_projects_panel.md in project memory): a web page ingested
     * via [ch.arcticsoft.springchat3.web.WebPageController] has no PDF/raw
     * file of its own the way every other document source (upload, Drive
     * folder sync, Working Document export) does - passing null simply skips
     * [persistBytes] below, leaving this document with no `document.pdf`,
     * the exact same state [getBytes] already handles for any pre-2026-08-22
     * document that predates bytes being stored at all.
     */
    fun store(
        filename: String,
        text: String,
        bytes: ByteArray?,
        projectId: String? = null,
        driveFolderLocalId: String? = null,
        rawFilename: String = DEFAULT_RAW_FILENAME,
    ): String {
        val documentId = UUID.randomUUID().toString()
        val document = ExtractedDocument(filename, text, System.currentTimeMillis(), projectId, driveFolderLocalId, rawFilename)
        documents[documentId] = document
        persist(documentId, document)
        if (bytes != null) {
            persistBytes(documentId, projectId, driveFolderLocalId, rawFilename, bytes)
        }
        return documentId
    }

    /** Returns the document stored under [documentId], or null if there is none (never uploaded, or an unrecognized id). */
    fun get(documentId: String): ExtractedDocument? = documents[documentId]

    /**
     * Returns [documentId]'s original PDF bytes, or null if there are none -
     * either [documentId] itself doesn't exist, or (2026-08-22, see this
     * class's own doc comment) it's a document stored before raw bytes were
     * kept at all. Looks up [documentId]'s own recorded [ExtractedDocument.projectId]/
     * [ExtractedDocument.driveFolderLocalId] first (2026-08-23) to resolve
     * the right directory - unlike before this feature, [documentId] alone
     * is no longer enough to compute the path.
     */
    fun getBytes(documentId: String): ByteArray? {
        val document = documents[documentId] ?: return null
        val file = documentRawFile(documentId, document.projectId, document.driveFolderLocalId, document.rawFilename)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            log.warn("Could not read raw bytes for document {} from {}", documentId, file, e)
            null
        }
    }

    /** All stored documents as lightweight summaries, oldest upload first - backs the side panel's document list. */
    fun list(): List<DocumentSummary> = synchronized(documents) {
        documents.map { (id, doc) -> DocumentSummary(id, doc.filename, doc.text.length, doc.projectId) }
    }

    /**
     * Removes the document stored under [documentId]. Returns true if a
     * document was actually removed, false if [documentId] wasn't found.
     * Also deletes `document.pdf` if this document has one (2026-08-22, see
     * this class's own doc comment) - a no-op `delete()` call for a
     * pre-existing document that never had raw bytes stored. Deletes this
     * document's whole on-disk directory *if* it's empty after removing both
     * files - it usually isn't yet at that point, since
     * [ch.arcticsoft.springchat3.web.DocumentController.delete] also calls
     * [DocumentIndex.remove] for the same [documentId], which still has its
     * own `vectorstore.json` to clean up there.
     *
     * **Call this LAST among the three per-document `remove` calls
     * (2026-08-23, changed alongside [ExtractedDocument.projectId]):**
     * unlike before this feature, order now matters - [DocumentIndex.remove]/
     * [DocumentStructureStore.remove] resolve their own files via
     * [documentDir], which needs [documentId]'s entry to still be present
     * here to know whether it lived under a project folder. Calling this
     * first would make [documentDir] return null for an already-removed
     * project-scoped document, silently leaving its `vectorstore.json`/
     * `structure.json` behind as orphaned files instead of actually deleting
     * them. See [ch.arcticsoft.springchat3.web.DocumentController.delete]
     * and [ch.arcticsoft.springchat3.web.DriveController]'s `ingestFile`/
     * `ingestGoogleDoc` for the corrected call order.
     */
    fun remove(documentId: String): Boolean {
        val document = documents.remove(documentId) ?: return false
        documentFile(documentId, document.projectId, document.driveFolderLocalId).delete()
        documentRawFile(documentId, document.projectId, document.driveFolderLocalId, document.rawFilename).delete()
        documentDirFor(documentId, document.projectId, document.driveFolderLocalId).delete()
        // Does not also try to delete the now-possibly-empty gdrive-<id>
        // folder itself (2026-08-23, see this class's own doc comment) -
        // same "leaves an empty container behind, not addressed" precedent
        // this already accepted for a project's own folder never being
        // cleaned up either, once its last document is removed.
        return true
    }
}

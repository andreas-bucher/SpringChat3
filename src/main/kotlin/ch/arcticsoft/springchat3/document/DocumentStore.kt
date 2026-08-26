package ch.arcticsoft.springchat3.document

import ch.arcticsoft.springchat3.project.ProjectStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
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
 * [spaceId] (2026-08-23, user's own request "when uploading a file or link
 * a google drive folder or link a working document then save the files in
 * the project folder of the active project" - see
 * springchat3_projects_panel.md in project memory) is null for a document
 * uploaded/synced with no active project - the pre-existing, still-supported
 * case - or the id of the project that was active at the time it was
 * ingested otherwise. Nullable with a default so a pre-2026-08-23
 * `document.json` with no `spaceId` key at all still deserializes cleanly
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
 * default reasoning as [spaceId] above.
 */
data class ExtractedDocument(
    val filename: String,
    val text: String,
    val uploadedAt: Long,
    val spaceId: String? = null,
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
 * The cached PDF rendering of a document that isn't already a PDF - written
 * by [DocumentStore.storePreview], served by
 * [ch.arcticsoft.springchat3.web.DocumentController.preview] (2026-08-23,
 * see [ch.arcticsoft.springchat3.document.PdfPreviewService]). A sibling of
 * the raw file in the document's own directory, so it moves and deletes with
 * the document across all four storage layouts without any bookkeeping of
 * its own - exactly like the `previous-` undo copy.
 */
const val PREVIEW_FILENAME = "preview.pdf"

/**
 * The sha256 of the raw bytes [PREVIEW_FILENAME] was built from. This is what
 * makes a stale preview structurally impossible rather than a thing every
 * write path has to remember to prevent: the preview is valid iff this
 * matches a fresh hash of what's on disk now, so an edit, an undo, a restored
 * backup or a future write path nobody has thought of yet all invalidate it
 * for free.
 */
const val PREVIEW_HASH_FILENAME = "preview.sha256"

/**
 * One stored document's identity/metadata for the UI's document list (see
 * [DocumentStore.list]) - deliberately without [ExtractedDocument.text],
 * which can be tens of thousands of characters and never needs to reach the
 * browser (see [ch.arcticsoft.springchat3.agent.ChatRequest.documentId]'s
 * doc comment for why the extracted text itself stays server-side).
 *
 * [spaceId] (2026-08-23, user's own request "The right panel shall display
 * the project resources of the selected project of the left panel" - see
 * springchat3_projects_panel.md in project memory) mirrors
 * [ExtractedDocument.spaceId] - null for a document with no active project
 * at ingest time. index.html filters its document/folder/working-doc lists
 * against `activeProjectId` using exactly this field, so the right panel
 * shows only the selected project's own resources.
 */
data class DocumentSummary(
    val documentId: String,
    val filename: String,
    val characterCount: Int,
    val spaceId: String? = null,
    /**
     * When this document was added, from [ExtractedDocument.uploadedAt]
     * (2026-08-25, sorting the resource list - see
     * springchat3_resource_sorting.md in project memory).
     *
     * It was missing until then, which was a quiet bug as well as a gap:
     * index.html's uploaded-document card already rendered
     * `formatUploadedAt(doc.uploadedAt)`, so every PDF card has been showing
     * a bare "Uploaded" with no date since the card was written. Defaulted so
     * a caller that does not have the document to hand still compiles.
     */
    val uploadedAt: Long = 0,
)

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
 * active at ingest time ([ExtractedDocument.spaceId], own doc comment) and
 * whether the document came from a linked Google Drive folder
 * ([ExtractedDocument.driveFolderLocalId], own doc comment) - `[dataDir]/<documentId>/`
 * (neither), `[dataDir]/gdrive-<driveFolderLocalId>/<documentId>/`
 * (Drive-sourced, no active project), `[dataDir]/spaces/<spaceId>/<documentId>/`
 * (uploaded/linked with a project active), or
 * `[dataDir]/spaces/<spaceId>/gdrive-<driveFolderLocalId>/<documentId>/`
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
     * the active project's own folder if [spaceId] is set, otherwise the
     * flat data directory - then, if [driveFolderLocalId] is also set
     * (2026-08-23, see [ExtractedDocument.driveFolderLocalId]'s own doc
     * comment), one further `gdrive-<driveFolderLocalId>` level inside that.
     * The two choices are independent, hence the up-to-four-layouts list in
     * this class's own doc comment.
     */
    private fun documentContainerFor(spaceId: String?, driveFolderLocalId: String?): File {
        val base = if (spaceId != null) projectStore.spaceDir(spaceId) else File(dataDir)
        return if (driveFolderLocalId != null) File(base, "gdrive-$driveFolderLocalId") else base
    }

    private fun documentDirFor(documentId: String, spaceId: String?, driveFolderLocalId: String?): File =
        File(documentContainerFor(spaceId, driveFolderLocalId), documentId)

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
        documents[documentId]?.let { documentDirFor(documentId, it.spaceId, it.driveFolderLocalId) }

    private fun documentFile(documentId: String, spaceId: String?, driveFolderLocalId: String?) =
        File(documentDirFor(documentId, spaceId, driveFolderLocalId), "document.json")

    private fun documentRawFile(documentId: String, spaceId: String?, driveFolderLocalId: String?, rawFilename: String) =
        File(documentDirFor(documentId, spaceId, driveFolderLocalId), rawFilename)

    /**
     * The one-level undo copy [backupBytes] writes and [getPreviousBytes]
     * reads (2026-08-23, Word editing tools - the user chose "edit in place,
     * keep one undo copy" when asked). Sits beside the live raw file in the
     * same document directory, so it moves/deletes with the document and
     * needs no bookkeeping of its own; deliberately only ONE generation deep
     * - a second edit overwrites the first edit's backup, which is what
     * "one-level undo" means.
     */
    private fun documentPreviousRawFile(documentId: String, spaceId: String?, driveFolderLocalId: String?, rawFilename: String) =
        File(documentDirFor(documentId, spaceId, driveFolderLocalId), "previous-$rawFilename")

    private fun documentPreviewFile(documentId: String, spaceId: String?, driveFolderLocalId: String?) =
        File(documentDirFor(documentId, spaceId, driveFolderLocalId), PREVIEW_FILENAME)

    private fun documentPreviewHashFile(documentId: String, spaceId: String?, driveFolderLocalId: String?) =
        File(documentDirFor(documentId, spaceId, driveFolderLocalId), PREVIEW_HASH_FILENAME)

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
        // "spaces" subdirectory itself holds per-space data, not a
        // document/gdrive container, so it's explicitly excluded here rather
        // than walked as if it were one.
        loadContainer(root, exclude = setOf("spaces"))
        // Documents nested one level deeper under each project's own folder.
        File(root, "spaces").listFiles { file -> file.isDirectory }?.forEach { spaceDir -> loadContainer(spaceDir) }
        loaded.sortBy { (_, doc) -> doc.uploadedAt }
        val map = LinkedHashMap<String, ExtractedDocument>()
        loaded.forEach { (id, doc) -> map[id] = doc }
        return map
    }

    private fun persist(documentId: String, document: ExtractedDocument) {
        try {
            val dir = documentDirFor(documentId, document.spaceId, document.driveFolderLocalId)
            dir.mkdirs()
            objectMapper.writeValue(documentFile(documentId, document.spaceId, document.driveFolderLocalId), document)
        } catch (e: Exception) {
            log.warn("Could not persist document {} to {}", documentId, documentFile(documentId, document.spaceId, document.driveFolderLocalId), e)
        }
    }

    private fun persistBytes(documentId: String, spaceId: String?, driveFolderLocalId: String?, rawFilename: String, bytes: ByteArray) {
        try {
            val dir = documentDirFor(documentId, spaceId, driveFolderLocalId)
            dir.mkdirs()
            documentRawFile(documentId, spaceId, driveFolderLocalId, rawFilename).writeBytes(bytes)
        } catch (e: Exception) {
            log.warn("Could not persist raw bytes for document {} to {}", documentId, documentRawFile(documentId, spaceId, driveFolderLocalId, rawFilename), e)
        }
    }

    /**
     * Stores [text] extracted from [filename] plus [bytes], the document's
     * original PDF content (2026-08-22, see this class's own doc comment) -
     * returns a fresh id to look either one up by later ([get]/[getBytes]).
     * [spaceId] (2026-08-23, see [ExtractedDocument.spaceId]'s own doc
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
        spaceId: String? = null,
        driveFolderLocalId: String? = null,
        rawFilename: String = DEFAULT_RAW_FILENAME,
    ): String {
        val documentId = UUID.randomUUID().toString()
        val document = ExtractedDocument(filename, text, System.currentTimeMillis(), spaceId, driveFolderLocalId, rawFilename)
        documents[documentId] = document
        persist(documentId, document)
        if (bytes != null) {
            persistBytes(documentId, spaceId, driveFolderLocalId, rawFilename, bytes)
        }
        return documentId
    }

    /** Returns the document stored under [documentId], or null if there is none (never uploaded, or an unrecognized id). */
    fun get(documentId: String): ExtractedDocument? = documents[documentId]

    /**
     * Returns [documentId]'s original PDF bytes, or null if there are none -
     * either [documentId] itself doesn't exist, or (2026-08-22, see this
     * class's own doc comment) it's a document stored before raw bytes were
     * kept at all. Looks up [documentId]'s own recorded [ExtractedDocument.spaceId]/
     * [ExtractedDocument.driveFolderLocalId] first (2026-08-23) to resolve
     * the right directory - unlike before this feature, [documentId] alone
     * is no longer enough to compute the path.
     */
    fun getBytes(documentId: String): ByteArray? {
        val document = documents[documentId] ?: return null
        val file = documentRawFile(documentId, document.spaceId, document.driveFolderLocalId, document.rawFilename)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            log.warn("Could not read raw bytes for document {} from {}", documentId, file, e)
            null
        }
    }

    /**
     * Replaces [documentId]'s extracted [text] and raw [bytes] in place,
     * keeping the same id, filename, project, upload time and raw filename
     * (2026-08-23, added for the Word editing tools - see
     * [WordDocumentWorkspace]). Returns false for an unknown id.
     *
     * Keeping the id is the whole point: an edited document stays selected
     * in the side panel, stays attached to the conversation, and keeps its
     * place in [WordDocumentStore] - all of which key on `documentId`. The
     * alternative ([store], which always mints a fresh id) would silently
     * detach a document the moment the model edited it.
     *
     * Does NOT touch [DocumentIndex]: the caller re-indexes, because
     * [DocumentIndex.index] appends rather than replaces, so a caller has to
     * `remove` first - see [WordDocumentWorkspace]'s own edit path.
     */
    fun update(documentId: String, text: String, bytes: ByteArray): Boolean {
        val existing = documents[documentId] ?: return false
        val updated = existing.copy(text = text)
        documents[documentId] = updated
        persist(documentId, updated)
        persistBytes(documentId, updated.spaceId, updated.driveFolderLocalId, updated.rawFilename, bytes)
        return true
    }

    /**
     * Copies [documentId]'s current raw bytes aside as the one-level undo
     * copy, overwriting any previous one. Returns false if there's nothing
     * to copy (unknown id, or a document stored without raw bytes at all).
     */
    fun backupBytes(documentId: String): Boolean {
        val document = documents[documentId] ?: return false
        val current = documentRawFile(documentId, document.spaceId, document.driveFolderLocalId, document.rawFilename)
        if (!current.exists()) return false
        return try {
            current.copyTo(
                documentPreviousRawFile(documentId, document.spaceId, document.driveFolderLocalId, document.rawFilename),
                overwrite = true,
            )
            true
        } catch (e: Exception) {
            log.warn("Could not back up raw bytes for document {} before an edit", documentId, e)
            false
        }
    }

    /** The undo copy written by [backupBytes], or null if this document has never been edited. */
    fun getPreviousBytes(documentId: String): ByteArray? {
        val document = documents[documentId] ?: return null
        val file = documentPreviousRawFile(documentId, document.spaceId, document.driveFolderLocalId, document.rawFilename)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            log.warn("Could not read the undo copy for document {} from {}", documentId, file, e)
            null
        }
    }

    /** The cached PDF rendering written by [storePreview], or null if this document has none yet. */
    fun getPreviewBytes(documentId: String): ByteArray? {
        val document = documents[documentId] ?: return null
        val file = documentPreviewFile(documentId, document.spaceId, document.driveFolderLocalId)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            log.warn("Could not read the PDF preview for document {} from {}", documentId, file, e)
            null
        }
    }

    /** The source hash the cached preview was built from - see [PREVIEW_HASH_FILENAME]. Null if there is no preview. */
    fun previewHash(documentId: String): String? {
        val document = documents[documentId] ?: return null
        val file = documentPreviewHashFile(documentId, document.spaceId, document.driveFolderLocalId)
        if (!file.exists()) return null
        return try {
            file.readText().trim().ifBlank { null }
        } catch (e: Exception) {
            log.warn("Could not read the PDF preview hash for document {} from {}", documentId, file, e)
            null
        }
    }

    /**
     * Caches [pdfBytes] as [documentId]'s preview, stamped with [sourceHash].
     *
     * The hash file is written *after* the PDF, never before: a crash between
     * the two leaves a preview with no hash, which reads as stale and gets
     * rebuilt. The other order would leave a hash claiming a PDF that was
     * never written, which reads as valid.
     */
    fun storePreview(documentId: String, pdfBytes: ByteArray, sourceHash: String): Boolean {
        val document = documents[documentId] ?: return false
        return try {
            documentDirFor(documentId, document.spaceId, document.driveFolderLocalId).mkdirs()
            documentPreviewFile(documentId, document.spaceId, document.driveFolderLocalId).writeBytes(pdfBytes)
            documentPreviewHashFile(documentId, document.spaceId, document.driveFolderLocalId).writeText(sourceHash)
            true
        } catch (e: Exception) {
            log.warn("Could not cache the PDF preview for document {}", documentId, e)
            false
        }
    }

    /** All stored documents as lightweight summaries, oldest upload first - backs the side panel's document list. */
    fun list(): List<DocumentSummary> = synchronized(documents) {
        documents.map { (id, doc) -> DocumentSummary(id, doc.filename, doc.text.length, doc.spaceId, doc.uploadedAt) }
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
     * (2026-08-23, changed alongside [ExtractedDocument.spaceId]):**
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
    /**
     * Moves [documentId] into [targetSpaceId] - the whole on-disk directory,
     * not just the recorded space (2026-08-25, user's own request to drag a
     * document from one space to another). Returns false for an unknown id,
     * for a Drive-folder file, or if the directory could not be moved.
     *
     * **The directory IS the move.** `vectorstore.json`, `structure.json`,
     * the raw bytes, the one-level undo copy and the cached PDF preview all
     * live inside it, and [DocumentIndex]/[DocumentStructureStore] resolve
     * their own files through [documentDir] *at call time* - so relocating
     * the directory relocates the embeddings with it and nothing needs
     * reindexing. This is the exact opposite of [remove]'s ordering rule,
     * where those two must be cleaned out first: here they must not be
     * touched at all.
     *
     * **A Drive-folder file is refused**, not moved. It lives under
     * `gdrive-<localId>/` and is owned by the sync: the next "Sync now"
     * would recreate it in the space the folder is linked to, leaving a
     * duplicate and no way to tell which one is current. Moving a whole
     * linked folder is a different operation and does not exist yet.
     *
     * **Fails without a half-move.** If the rename fails nothing is changed
     * at all; if the metadata write then fails the directory is moved back,
     * because a document whose recorded [ExtractedDocument.spaceId] and
     * physical location disagree is invisible to [loadPersisted] after a
     * restart - it would be read from wherever it sits but resolve its path
     * from what the JSON says.
     */
    fun moveToSpace(documentId: String, targetSpaceId: String?): Boolean {
        val document = documents[documentId] ?: return false
        if (document.driveFolderLocalId != null) return false
        if (document.spaceId == targetSpaceId) return true

        val from = documentDirFor(documentId, document.spaceId, null)
        val to = documentDirFor(documentId, targetSpaceId, null)
        val moved = document.copy(spaceId = targetSpaceId)

        if (from.exists()) {
            try {
                to.parentFile.mkdirs()
                Files.move(from.toPath(), to.toPath())
            } catch (e: Exception) {
                log.warn("Could not move document {} from {} to {} - leaving it where it is", documentId, from, to, e)
                return false
            }
        }

        // Written here rather than through persist(), which logs and swallows:
        // a failed write after a successful rename is the one case that would
        // leave disk and metadata disagreeing, so it is undone instead.
        try {
            val dir = documentDirFor(documentId, targetSpaceId, null)
            dir.mkdirs()
            objectMapper.writeValue(documentFile(documentId, targetSpaceId, null), moved)
        } catch (e: Exception) {
            log.warn("Could not record document {}'s new space - moving it back to {}", documentId, from, e)
            try {
                if (to.exists()) Files.move(to.toPath(), from.toPath())
            } catch (rollback: Exception) {
                log.error(
                    "Document {} is now at {} while its document.json still says space {} - it will not be found " +
                        "after a restart until one of the two is corrected by hand",
                    documentId,
                    to,
                    document.spaceId,
                    rollback,
                )
            }
            return false
        }

        documents[documentId] = moved
        return true
    }

    fun remove(documentId: String): Boolean {
        val document = documents.remove(documentId) ?: return false
        documentFile(documentId, document.spaceId, document.driveFolderLocalId).delete()
        documentRawFile(documentId, document.spaceId, document.driveFolderLocalId, document.rawFilename).delete()
        documentPreviousRawFile(documentId, document.spaceId, document.driveFolderLocalId, document.rawFilename).delete()
        documentPreviewFile(documentId, document.spaceId, document.driveFolderLocalId).delete()
        documentPreviewHashFile(documentId, document.spaceId, document.driveFolderLocalId).delete()
        documentDirFor(documentId, document.spaceId, document.driveFolderLocalId).delete()
        // Does not also try to delete the now-possibly-empty gdrive-<id>
        // folder itself (2026-08-23, see this class's own doc comment) -
        // same "leaves an empty container behind, not addressed" precedent
        // this already accepted for a project's own folder never being
        // cleaned up either, once its last document is removed.
        return true
    }
}

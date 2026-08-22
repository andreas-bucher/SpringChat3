package ch.arcticsoft.springchat3.document

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
 */
data class ExtractedDocument(val filename: String, val text: String, val uploadedAt: Long)

/**
 * One stored document's identity/metadata for the UI's document list (see
 * [DocumentStore.list]) - deliberately without [ExtractedDocument.text],
 * which can be tens of thousands of characters and never needs to reach the
 * browser (see [ch.arcticsoft.springchat3.agent.ChatRequest.documentId]'s
 * doc comment for why the extracted text itself stays server-side).
 */
data class DocumentSummary(val documentId: String, val filename: String, val characterCount: Int)

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
 * each document gets its own `[dataDir]/<documentId>/document.json`. This
 * was requested explicitly, not just a guess at what's "better" - the
 * earlier single-file design rewrote *every* stored document's full text to
 * disk on every single store/remove, an O(all documents) cost paid per
 * mutation that only gets worse as documents accumulate, which is exactly
 * what this feature is for. Per-document files make each mutation O(one
 * document), and bound a corrupt-file load failure to that one document
 * instead of losing every stored document at once (see [loadPersisted]'s
 * per-file try/catch). [DocumentIndex]'s vector-store persistence went
 * through the same change for the same reason, into its own per-document
 * `vectorstore.json` alongside this one.
 *
 * **Also holds the original PDF bytes, same directory, since 2026-08-22**
 * (user's own idea "would it be possible to enable the files to be
 * displayed on another browser tab?" - see springchat3_document_qa.md in
 * project memory for the "open in a new tab" feature this enabled): each
 * document's raw bytes live alongside its `document.json`, as a sibling
 * `[dataDir]/<documentId>/document.pdf` - see [getBytes]. Kept as a plain
 * file rather than folded into [ExtractedDocument] itself (e.g. as a
 * base64 field) so [documentFile] stays small, JSON-only metadata, and the
 * raw bytes can be read/streamed back independently without ever having to
 * parse-then-decode them out of a JSON document first. **A document stored
 * before this change has no `document.pdf`** - [getBytes] simply returns
 * null for one of those rather than treating it as an error; the side
 * panel's "open in a new tab" button surfaces that as an ordinary 404, not
 * a special case this store needs to know about.
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
) {
    private val log = LoggerFactory.getLogger(DocumentStore::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val documents = Collections.synchronizedMap(loadPersisted())

    private fun documentDir(documentId: String) = File(dataDir, documentId)

    private fun documentFile(documentId: String) = File(documentDir(documentId), "document.json")

    private fun documentPdfFile(documentId: String) = File(documentDir(documentId), "document.pdf")

    private fun loadPersisted(): LinkedHashMap<String, ExtractedDocument> {
        val root = File(dataDir)
        root.mkdirs()
        val loaded = mutableListOf<Pair<String, ExtractedDocument>>()
        root.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val docFile = File(dir, "document.json")
            if (docFile.exists()) {
                try {
                    loaded += dir.name to objectMapper.readValue<ExtractedDocument>(docFile)
                } catch (e: Exception) {
                    log.warn("Could not load persisted document from {} - skipping", docFile, e)
                }
            }
        }
        loaded.sortBy { (_, doc) -> doc.uploadedAt }
        val map = LinkedHashMap<String, ExtractedDocument>()
        loaded.forEach { (id, doc) -> map[id] = doc }
        return map
    }

    private fun persist(documentId: String, document: ExtractedDocument) {
        try {
            val dir = documentDir(documentId)
            dir.mkdirs()
            objectMapper.writeValue(documentFile(documentId), document)
        } catch (e: Exception) {
            log.warn("Could not persist document {} to {}", documentId, documentFile(documentId), e)
        }
    }

    private fun persistBytes(documentId: String, bytes: ByteArray) {
        try {
            val dir = documentDir(documentId)
            dir.mkdirs()
            documentPdfFile(documentId).writeBytes(bytes)
        } catch (e: Exception) {
            log.warn("Could not persist raw PDF bytes for document {} to {}", documentId, documentPdfFile(documentId), e)
        }
    }

    /**
     * Stores [text] extracted from [filename] plus [bytes], the document's
     * original PDF content (2026-08-22, see this class's own doc comment) -
     * returns a fresh id to look either one up by later ([get]/[getBytes]).
     * A failure persisting [bytes] is logged and otherwise swallowed, same
     * as [persist] itself - this store already tolerates a failed metadata
     * write without failing the whole upload/sync, so a failed bytes write
     * gets the same treatment rather than being held to a stricter standard;
     * the practical effect is just that [getBytes] later returns null for
     * this [documentId], same as any other pre-existing document that never
     * had its bytes stored.
     */
    fun store(filename: String, text: String, bytes: ByteArray): String {
        val documentId = UUID.randomUUID().toString()
        val document = ExtractedDocument(filename, text, System.currentTimeMillis())
        documents[documentId] = document
        persist(documentId, document)
        persistBytes(documentId, bytes)
        return documentId
    }

    /** Returns the document stored under [documentId], or null if there is none (never uploaded, or an unrecognized id). */
    fun get(documentId: String): ExtractedDocument? = documents[documentId]

    /**
     * Returns [documentId]'s original PDF bytes, or null if there are none -
     * either [documentId] itself doesn't exist, or (2026-08-22, see this
     * class's own doc comment) it's a document stored before raw bytes were
     * kept at all. Deliberately does NOT check [documents] first to
     * distinguish those two null cases - callers (see
     * [ch.arcticsoft.springchat3.web.DocumentController.file]) already
     * treat "no bytes" as a plain 404 either way, so there's no behavioral
     * difference worth the extra lookup.
     */
    fun getBytes(documentId: String): ByteArray? {
        val file = documentPdfFile(documentId)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            log.warn("Could not read raw PDF bytes for document {} from {}", documentId, file, e)
            null
        }
    }

    /** All stored documents as lightweight summaries, oldest upload first - backs the side panel's document list. */
    fun list(): List<DocumentSummary> = synchronized(documents) {
        documents.map { (id, doc) -> DocumentSummary(id, doc.filename, doc.text.length) }
    }

    /**
     * Removes the document stored under [documentId]. Returns true if a
     * document was actually removed, false if [documentId] wasn't found.
     * Also deletes `document.pdf` if this document has one (2026-08-22, see
     * this class's own doc comment) - a no-op `delete()` call for a
     * pre-existing document that never had raw bytes stored. Deletes this
     * document's whole on-disk directory *if* it's empty after removing
     * both files - it usually isn't yet at that point, since
     * [ch.arcticsoft.springchat3.web.DocumentController.delete] also calls
     * [DocumentIndex.remove] for the same [documentId], which still has its
     * own `vectorstore.json` to clean up there. Order between the two
     * doesn't matter: whichever runs second finds the directory empty and
     * actually removes it - `File.delete()` on a non-empty directory is a
     * harmless no-op, not an error.
     */
    fun remove(documentId: String): Boolean {
        val removed = documents.remove(documentId) != null
        if (removed) {
            documentFile(documentId).delete()
            documentPdfFile(documentId).delete()
            documentDir(documentId).delete()
        }
        return removed
    }
}

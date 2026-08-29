package ch.arcticsoft.springchat3.document

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A Word document as the chat tools address it: by [filename], never by id.
 * [documentId] stays internal to this class - the LLM is never shown one and
 * never has to echo one back, which removes a whole class of "the model
 * invented a UUID" failure and keeps tool inputs readable in the trace.
 */
data class WordDocumentRef(val documentId: String, val filename: String)

/**
 * Everything the Word chat tools need that isn't docx4j itself (2026-08-23,
 * user's own request "create tools to read and write ms word documents. use
 * docx4j.") - resolving a document by name inside the active project,
 * reading its paragraphs, and applying an edit as one atomic
 * back-up-then-write-then-re-index step.
 *
 * Sits between [WordDocumentService] (pure .docx manipulation, no app
 * knowledge) and the two tool classes
 * ([ch.arcticsoft.springchat3.tools.WordDocumentReadTool] /
 * [ch.arcticsoft.springchat3.tools.WordDocumentEditTool], both per-request,
 * both LLM-facing). The split exists because the tools are constructed fresh
 * per chat turn - they must stay free of injected state - while this is an
 * ordinary singleton bean holding the stores.
 *
 * **Only ever touches uploaded Word documents** ([WordDocumentStore]) inside
 * one project: a PDF, a Drive-synced file, a linked Google Doc and a linked
 * web page are all invisible here, and so is every other project's content.
 * That's the containment boundary for the whole feature - a model can't
 * reach a document the user isn't currently working in, and can't corrupt a
 * document type this app has no way to write back.
 */
@Component
class WordDocumentWorkspace(
    private val wordDocumentStore: WordDocumentStore,
    private val documentStore: DocumentStore,
    private val documentIndex: DocumentIndex,
    private val wordDocumentService: WordDocumentService,
    private val pdfPreviewService: PdfPreviewService,
) {
    private val log = LoggerFactory.getLogger(WordDocumentWorkspace::class.java)

    /**
     * One lock per documentId so two edits in the same turn (the model can
     * call several tools in sequence) can't interleave read-modify-write on
     * the same file. Never cleaned up - one small object per document ever
     * edited in this process's lifetime, which is nothing.
     */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Every Word document in [spaceId] - names only, deliberately no
     * paragraph counts: this runs on every single tool call (see [resolve]),
     * and counting would mean a full docx4j parse per document per call.
     * [paragraphCount] does that part, only where a count is actually shown.
     *
     * [onlyDocumentIds] narrows that to a subset (2026-08-23, user's own
     * report: with nothing selected in the side panel, `documentEdit` still
     * ran "against 2 Word document(s)"; then, with one document selected,
     * the reply still "contained aspects" from the other one). Every chat
     * tool now passes the user's side-panel selection - the reading ones as
     * well as the editing ones - so a document the user did not point at is
     * not merely discouraged as a target, it is invisible for the whole
     * turn. Null means the whole project and no caller passes it today.
     */
    fun list(spaceId: String?, onlyDocumentIds: Set<String>? = null): List<WordDocumentRef> =
        wordDocumentStore.getAll()
            .filter { it.spaceId == spaceId }
            .filter { onlyDocumentIds == null || it.documentId in onlyDocumentIds }
            .map { WordDocumentRef(it.documentId, it.filename) }

    /** Paragraph count for one document, or null if its file can't be read/parsed. */
    fun paragraphCount(ref: WordDocumentRef): Int? = try {
        paragraphs(ref).size
    } catch (e: Exception) {
        log.warn("Could not read Word document {}", ref.filename, e)
        null
    }

    /**
     * Resolves the name the model used to one document in [spaceId]:
     * case-insensitive exact match first, then a unique
     * case-insensitive substring match (so "spec" finds "Spec v2.docx"),
     * and nothing otherwise. Ambiguity is never guessed at - two matches
     * resolve to null and the caller reports the candidates back to the
     * model, which is far better than silently editing the wrong file.
     */
    fun resolve(spaceId: String?, filename: String, onlyDocumentIds: Set<String>? = null): WordDocumentRef? {
        val candidates = list(spaceId, onlyDocumentIds)
        val needle = filename.trim()
        candidates.firstOrNull { it.filename.equals(needle, ignoreCase = true) }?.let { return it }
        val partial = candidates.filter { it.filename.contains(needle, ignoreCase = true) }
        return partial.singleOrNull()
    }

    fun paragraphs(ref: WordDocumentRef): List<WordParagraph> {
        val bytes = documentStore.getBytes(ref.documentId) ?: return emptyList()
        return wordDocumentService.paragraphs(bytes)
    }

    /** The styles this document defines - see [WordStyle]. Empty when it has no styles part at all. */
    fun styles(ref: WordDocumentRef): List<WordStyle> {
        val bytes = documentStore.getBytes(ref.documentId) ?: return emptyList()
        return wordDocumentService.styles(bytes)
    }

    /**
     * The document's formatting picture, or null when its bytes are gone -
     * distinguished from an empty report on purpose, since "no such document"
     * and "a document with no formatting" are different things to tell the
     * user.
     */
    fun formatting(ref: WordDocumentRef): WordFormattingReport? {
        val bytes = documentStore.getBytes(ref.documentId) ?: return null
        return wordDocumentService.formatting(bytes)
    }

    /**
     * The one write path: hand the current bytes to [edit], and if it
     * produced anything, back the old ones up for undo, persist the new
     * ones, re-extract the plain text and re-embed it. Returns the
     * document's new paragraph count, or null if [edit] returned null
     * meaning "nothing to change".
     *
     * That null case matters more than it looks: a find/replace that matched
     * nothing must NOT go through the write path, because backing up
     * identical bytes would quietly destroy the undo copy of the user's
     * previous, real edit.
     *
     * [DocumentIndex.remove] before [DocumentIndex.index] is not optional -
     * `index` appends to a document's vector store rather than replacing it
     * (see its own doc comment), so skipping the remove would leave the
     * pre-edit chunks searchable alongside the new ones and let the model
     * answer from text no longer in the document.
     */
    fun applyEdit(ref: WordDocumentRef, edit: (ByteArray) -> ByteArray?): Int? =
        synchronized(locks.computeIfAbsent(ref.documentId) { Any() }) {
            val current = documentStore.getBytes(ref.documentId)
                ?: throw IllegalStateException("\"${ref.filename}\" has no stored file to edit.")
            val updated = edit(current) ?: return@synchronized null
            documentStore.backupBytes(ref.documentId)
            persist(ref, updated)
        }

    /**
     * Restores the copy [applyEdit] took before the most recent edit - the
     * one-level undo the user chose over versioning. Returns the restored
     * paragraph count, or null if this document has no undo copy (never
     * edited, or already undone once - undo is not itself undoable).
     */
    fun undo(ref: WordDocumentRef): Int? =
        synchronized(locks.computeIfAbsent(ref.documentId) { Any() }) {
            val previous = documentStore.getPreviousBytes(ref.documentId) ?: return@synchronized null
            persist(ref, previous)
        }

    private fun persist(ref: WordDocumentRef, bytes: ByteArray): Int {
        val text = wordDocumentService.plainText(bytes)
        documentStore.update(ref.documentId, text, bytes)
        documentIndex.remove(ref.documentId)
        if (text.isNotBlank()) {
            documentIndex.index(ref.documentId, listOf(Document(UUID.randomUUID().toString(), text, emptyMap())))
        }
        // The single place every write lands - applyEdit AND undo - which is
        // exactly why the warm-up hook goes here rather than in each of them
        // ("after app has edited it", the user's own wording). Correctness
        // doesn't depend on it firing: PdfPreviewService hashes the source
        // bytes, so a missed warm-up costs one conversion, not a stale
        // document shown to the user.
        pdfPreviewService.warm(ref.documentId)
        val count = wordDocumentService.paragraphs(bytes).size
        log.info("Word document '{}' ({}) updated - {} paragraphs, {} chars", ref.filename, ref.documentId, count, text.length)
        return count
    }

    /**
     * Creates a brand new .docx in [spaceId] and registers it as an
     * uploaded Word document, so it appears in the right panel's Working
     * Documents section exactly like one the user uploaded themselves -
     * same store, same card, same × delete. Goes through
     * [DocumentStore.store] (a genuinely new document, new id) rather than
     * [DocumentStore.update].
     */
    fun create(spaceId: String?, filename: String, text: String): WordDocumentRef {
        val safeName = sanitizeFilename(filename)
        val bytes = wordDocumentService.create(text)
        val plain = wordDocumentService.plainText(bytes)
        val documentId = documentStore.store(safeName, plain, bytes, spaceId, rawFilename = RAW_DOCX_FILENAME)
        if (plain.isNotBlank()) {
            documentIndex.index(documentId, listOf(Document(UUID.randomUUID().toString(), plain, emptyMap())))
        }
        wordDocumentStore.add(UploadedWordDocument(documentId, safeName, System.currentTimeMillis(), spaceId))
        pdfPreviewService.warm(documentId)
        log.info("Created Word document '{}' ({}) in project {}", safeName, documentId, spaceId)
        return WordDocumentRef(documentId, safeName)
    }

    /**
     * Strips anything path-like out of a model-supplied name and forces a
     * .docx extension. The name never reaches the filesystem as-is (a
     * document's on-disk file is always `document.docx` inside its own
     * id-named directory - see [DocumentStore]), so this is about the name
     * the user sees in the panel, not path traversal - but a filename
     * containing a slash would still be confusing everywhere it's displayed.
     */
    private fun sanitizeFilename(filename: String): String {
        val base = filename.trim().substringAfterLast('/').substringAfterLast('\\').ifBlank { "Untitled" }
        return if (base.endsWith(".docx", ignoreCase = true)) base else "$base.docx"
    }

    companion object {
        /**
         * Must match [ch.arcticsoft.springchat3.web.WordDocumentController]'s
         * own constant of the same name - both name the file a Word
         * document's raw bytes live under, and
         * [ch.arcticsoft.springchat3.web.DocumentController.file] reads the
         * extension back off it to pick a Content-Type. Duplicated as a
         * private-to-this-package constant rather than imported from the web
         * layer: the document package shouldn't depend on a controller.
         */
        private const val RAW_DOCX_FILENAME = "document.docx"
    }
}

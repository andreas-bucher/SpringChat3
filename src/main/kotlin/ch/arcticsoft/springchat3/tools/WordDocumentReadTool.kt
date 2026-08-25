package ch.arcticsoft.springchat3.tools

import ch.arcticsoft.springchat3.document.WordDocumentRef
import ch.arcticsoft.springchat3.document.WordDocumentWorkspace
import ch.arcticsoft.springchat3.document.WordParagraph
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * Read-only access to the active project's uploaded Word documents, offered
 * to the tool-selection model in
 * [ch.arcticsoft.springchat3.agent.ChatAgent.analyzeMessage] (2026-08-23,
 * user's own decision when asked: "analyzeMessage can use tools to read the
 * document. editing a document should be made by answer." - the editing half
 * lives in [WordDocumentEditTool], attached to a different step and a bigger
 * model).
 *
 * Constructed per chat turn, not a `@Component`, for the same reason
 * [CurrentLocationTool] is: it needs this request's active project, which
 * differs turn to turn. [ChatToolRegistry] therefore never sees it, but its
 * [GatheringTool] marker still matters: ChatAgent's analyze-step list is
 * typed `List<GatheringTool>`, so this can go in it and an editing tool
 * cannot - see that registry's own doc comment.
 *
 * **Everything here is paged or summarized on purpose.** Whatever a tool
 * returns is fed straight back into the tool-selection model's context, and
 * that model is the small one (`granite4.1:3b` by default). A
 * `read_word_document` that dumped forty pages would break the turn, so the
 * design pushes the model towards outline → find → read a slice, rather than
 * "read the whole thing":
 *  - [listWordDocuments] is names and sizes only,
 *  - [getWordDocumentOutline] is headings only,
 *  - [findInWordDocument] returns matching paragraph numbers with snippets,
 *  - [readWordDocument] takes an explicit window and caps it hard.
 *
 * Paragraph numbers are 1-based in the tool surface and 0-based internally
 * (see [WordParagraph.index]) - LLMs handle "paragraph 1 is the first one"
 * far more reliably than 0-based indexing, and the conversion happens in one
 * place, here.
 */
class WordDocumentReadTool(
    private val workspace: WordDocumentWorkspace,
    private val spaceId: String?,
    /**
     * Restricts every tool here to these documents, or the whole project when
     * null (2026-08-23). **Both** steps that build one of these - the
     * gathering step and the editing step - pass the user's side-panel
     * selection, so nothing in a turn can read a document the user did not
     * attach. Null (whole project) is kept only because a tool that can see
     * everything is occasionally the right thing and this is where that
     * choice would be expressed; no caller passes it today.
     */
    private val scopeDocumentIds: Set<String>? = null,
) : GatheringTool {

    companion object {
        /** Hard cap on paragraphs returned by one [readWordDocument] call, whatever was asked for. */
        const val MAX_PARAGRAPHS_PER_READ = 40

        /** Characters kept per paragraph in a read/search result before truncating with an ellipsis. */
        const val MAX_PARAGRAPH_CHARS = 600

        /** Heading style ids treated as outline entries by [getWordDocumentOutline]. */
        private val HEADING_STYLES = setOf("title", "heading1", "heading2", "heading3", "heading4")
    }

    private fun clip(text: String): String =
        if (text.length <= MAX_PARAGRAPH_CHARS) text else text.take(MAX_PARAGRAPH_CHARS) + "…"

    private fun render(paragraphs: List<WordParagraph>): String =
        paragraphs.joinToString("\n") { p ->
            val style = p.style?.let { " [$it]" }.orEmpty()
            "${p.index + 1}.$style ${clip(p.text)}"
        }

    /**
     * Shared by every tool here: resolve, or return the JSON error the model
     * should relay. The `{"error": ...}` shape is this app's own convention
     * for a tool that couldn't do its job - [ch.arcticsoft.springchat3.agent.ChatAgent]
     * detects it and tells the generation model to surface the reason rather
     * than invent an answer.
     */
    private fun withDocument(filename: String, block: (WordDocumentRef) -> String): String {
        if (spaceId == null) {
            return """{"error": "No project is selected, so there are no Word documents available."}"""
        }
        val ref = workspace.resolve(spaceId, filename, scopeDocumentIds)
            ?: return """{"error": "No single Word document matches \"$filename\". Call list_word_documents to see the exact names available."}"""
        return try {
            block(ref)
        } catch (e: Exception) {
            """{"error": "Could not read \"${ref.filename}\": ${e.message}"}"""
        }
    }

    @Tool(
        name = "list_word_documents",
        description = "List the Word documents you are allowed to work with, with their exact " +
            "filenames and how many paragraphs each has. Call this first whenever the user refers to a Word " +
            "document without naming it exactly, so you can use the real filename in the other document tools. " +
            "Takes no parameters.",
    )
    fun listWordDocuments(): String {
        if (spaceId == null) return """{"error": "No project is selected, so there are no Word documents available."}"""
        val documents = workspace.list(spaceId, scopeDocumentIds)
        if (documents.isEmpty()) {
            // Which of the two it is matters to the model: "none exist" is a
            // dead end, "none selected" is something the user can fix, and
            // saying the wrong one invites it to insist the document isn't there.
            return if (scopeDocumentIds != null) {
                "The user has no Word document selected, so there is none to read. Ask them to select one in the side panel."
            } else {
                "This project has no Word documents."
            }
        }
        return documents.joinToString("\n") { ref ->
            val count = workspace.paragraphCount(ref)
            if (count == null) "- ${ref.filename} (could not be read)" else "- ${ref.filename} ($count paragraphs)"
        }
    }

    @Tool(
        name = "get_word_document_outline",
        description = "Get the headings of a Word document with their paragraph numbers - a cheap map of " +
            "the document's structure. Call this before reading a long document, to find which part to read.",
    )
    fun getWordDocumentOutline(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
    ): String = withDocument(filename) { ref ->
        val paragraphs = workspace.paragraphs(ref)
        val headings = paragraphs.filter { it.style?.lowercase()?.replace(" ", "") in HEADING_STYLES }
        if (headings.isEmpty()) {
            "\"${ref.filename}\" has no headings - it is ${paragraphs.size} paragraphs of plain text. " +
                "Use read_word_document or find_in_word_document instead."
        } else {
            "Outline of \"${ref.filename}\" (paragraph number, style, heading):\n" + render(headings)
        }
    }

    @Tool(
        name = "read_word_document",
        description = "Read a window of paragraphs from a Word document, numbered from 1, each prefixed with " +
            "its paragraph number and Word style. Never reads the whole document at once - ask for the part " +
            "you need (use get_word_document_outline or find_in_word_document first to locate it). At most " +
            "40 paragraphs come back per call.",
    )
    fun readWordDocument(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "First paragraph to read, counting from 1. Use 1 to start at the beginning.")
        fromParagraph: Int,
        @ToolParam(description = "How many paragraphs to read, at most 40")
        count: Int,
    ): String = withDocument(filename) { ref ->
        val all = workspace.paragraphs(ref)
        val from = (fromParagraph - 1).coerceAtLeast(0)
        if (from >= all.size) {
            "\"${ref.filename}\" only has ${all.size} paragraphs, so there is nothing at paragraph $fromParagraph."
        } else {
            val window = all.drop(from).take(count.coerceIn(1, MAX_PARAGRAPHS_PER_READ))
            val last = from + window.size
            val more = if (last < all.size) "\n(paragraphs ${last + 1}-${all.size} not shown)" else ""
            "\"${ref.filename}\", paragraphs ${from + 1}-$last of ${all.size}:\n" + render(window) + more
        }
    }

    @Tool(
        name = "find_in_word_document",
        description = "Find which paragraphs of a Word document contain some text, returning their paragraph " +
            "numbers and the matching paragraphs. Use this to locate the place to read or edit, instead of " +
            "reading through the document.",
    )
    fun findInWordDocument(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "The text to look for - matched case-insensitively anywhere in a paragraph")
        query: String,
    ): String = withDocument(filename) { ref ->
        if (query.isBlank()) {
            """{"error": "The search text is empty - say what to look for."}"""
        } else {
            val matches = workspace.paragraphs(ref).filter { it.text.contains(query, ignoreCase = true) }
            if (matches.isEmpty()) {
                "No paragraph of \"${ref.filename}\" contains \"$query\"."
            } else {
                val shown = matches.take(MAX_PARAGRAPHS_PER_READ)
                val more = if (matches.size > shown.size) "\n(${matches.size - shown.size} further matches not shown)" else ""
                "${matches.size} matching paragraph(s) in \"${ref.filename}\":\n" + render(shown) + more
            }
        }
    }
}

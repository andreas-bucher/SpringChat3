package ch.arcticsoft.springchat3.tools

import ch.arcticsoft.springchat3.document.WordDocumentRef
import ch.arcticsoft.springchat3.document.WordDocumentWorkspace
import ch.arcticsoft.springchat3.document.WordParagraph
import ch.arcticsoft.springchat3.document.WordParagraphFormat
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
 *  - [readWordDocument] takes an explicit window and caps it hard,
 *  - [listWordStyles] caps the list,
 *  - [analyzeWordFormatting] computes its counts here and sends only the
 *    conclusions - never XML, and never one line per paragraph.
 *
 * The last two arrived 2026-08-29 as the read half of formatting support
 * (springchat3_word_formatting_design.md in project memory). Until then the
 * model could see each paragraph's STYLE but nothing about the direct
 * formatting sitting on top of it - so the commonest defect in a
 * hand-formatted document, a heading that is really body text in bold, was
 * invisible, and so was the reason a font change silently did nothing. The
 * write half is deliberately not here yet.
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

        /** Most styles listed by [listWordStyles], and most paragraph numbers named in one line of a report. */
        const val MAX_STYLES_LISTED = 60
        const val MAX_NUMBERS_LISTED = 12
    }

    /** A style id as the comparisons here want it: lowercase, no spaces. */
    private fun normalizedStyle(style: String?): String? = style?.lowercase()?.replace(" ", "")

    private fun isHeading(style: String?): Boolean = normalizedStyle(style) in HEADING_STYLES

    /** 1-based paragraph numbers, capped - a report line must not become the whole document. */
    private fun numbers(paragraphs: List<WordParagraphFormat>): String {
        val shown = paragraphs.take(MAX_NUMBERS_LISTED).joinToString(", ") { (it.index + 1).toString() }
        val rest = paragraphs.size - MAX_NUMBERS_LISTED
        return if (rest > 0) "$shown and $rest more" else shown
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
        name = "list_word_styles",
        description = "List the paragraph and character styles a Word document actually defines, with their " +
            "ids and display names. Use it before talking about applying a style: a document written in " +
            "another language may not define \"Heading1\" at all, and a style it does not define does nothing.",
    )
    fun listWordStyles(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
    ): String = withDocument(filename) { ref ->
        val styles = workspace.styles(ref)
        if (styles.isEmpty()) {
            "\"${ref.filename}\" defines no styles of its own, so every paragraph uses Word's built-in defaults."
        } else {
            val shown = styles.take(MAX_STYLES_LISTED)
            val more = if (styles.size > shown.size) "\n(${styles.size - shown.size} further styles not shown)" else ""
            "${styles.size} style(s) defined by \"${ref.filename}\" (id, then display name):\n" +
                shown.joinToString("\n") { style ->
                    val name = style.name?.takeIf { it != style.styleId }?.let { " - \"$it\"" }.orEmpty()
                    "- ${style.styleId}$name"
                } + more
        }
    }

    /**
     * The formatting half of reading a document (2026-08-29). Everything
     * else here shows TEXT and its style; this shows what is actually
     * formatting the text, which the model was previously blind to.
     *
     * Deliberately a report and never XML, for the same reason every other
     * tool in this class is paged: its output goes straight into the small
     * tool-selection model's context. So the counts are computed here and
     * only the conclusions travel.
     *
     * The line that earns the tool is the direct-formatting count. Direct
     * run formatting beats both docDefaults and the paragraph's style, so a
     * document that arrived by paste overrides everything and a
     * document-wide font change appears to do absolutely nothing - see
     * springchat3_word_formatting_design.md in project memory.
     */
    @Tool(
        name = "analyze_word_formatting",
        description = "Report how a Word document is formatted: its default font and size, which fonts and " +
            "sizes actually appear, how many paragraphs override their style with direct formatting, " +
            "paragraphs that are entirely bold but not real headings, typed-in numbering that is not a real " +
            "Word list, and empty spacer paragraphs. Call this whenever the user asks about a document's " +
            "formatting, asks to improve or tidy it, or wonders why a formatting change had no effect.",
    )
    fun analyzeWordFormatting(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
    ): String = withDocument(filename) { ref ->
        val report = workspace.formatting(ref)
        val paragraphs = report?.paragraphs.orEmpty()
        if (report == null || paragraphs.isEmpty()) {
            return@withDocument "\"${ref.filename}\" has no paragraphs to analyze."
        }
        val overriding = paragraphs.filter { it.directlyFormattedRuns > 0 }
        val pseudoHeadings = paragraphs.filter { it.allRunsBold && !it.empty && !isHeading(it.style) }
        val manual = paragraphs.filter { it.manuallyNumbered && !it.listNumbered }
        val empties = paragraphs.filter { it.empty }
        val fonts = paragraphs.flatMap { it.fonts }.groupingBy { it }.eachCount()
        val sizes = paragraphs.flatMap { it.sizesPt }.distinct().sorted()
        val levels = paragraphs.mapNotNull { p ->
            val style = normalizedStyle(p.style)
            if (style != null && style.startsWith("heading")) style.removePrefix("heading").toIntOrNull() else null
        }
        val skips = levels.zipWithNext().count { (previous, next) -> next > previous + 1 }

        val defaults = when {
            report.defaultFont == null && report.defaultSizePt == null ->
                "Document default (docDefaults): none set - Word falls back to its own built-in default."
            else ->
                "Document default (docDefaults): ${report.defaultFont ?: "font not set"}" +
                    (report.defaultSizePt?.let { ", ${it}pt" } ?: ", size not set")
        }
        listOfNotNull(
            "Formatting of \"${ref.filename}\" (${paragraphs.size} paragraphs, ${report.styles.size} styles defined):",
            defaults,
            if (fonts.isEmpty()) {
                "Direct font settings: none - every run takes its font from its style or from docDefaults."
            } else {
                "Fonts set directly on runs: " + fonts.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key} (${it.value} paragraph(s))" }
            },
            if (sizes.isEmpty()) null else "Sizes set directly on runs: " + sizes.joinToString(", ") { "${it}pt" },
            "Paragraphs overriding their style with direct formatting: ${overriding.size} of ${paragraphs.size}" +
                if (overriding.isEmpty()) {
                    " - a change to docDefaults or to a style will apply everywhere."
                } else {
                    " - this is why a document-wide font or size change can appear to do nothing, since direct " +
                        "formatting wins over both the style and docDefaults."
                },
            if (levels.isEmpty()) {
                "Headings: none - this document has no heading styles at all."
            } else {
                "Headings: ${levels.size} across levels ${levels.distinct().sorted().joinToString(", ")}" +
                    if (skips > 0) " ($skips place(s) where a level is skipped)" else " (no level skipped)"
            },
            if (pseudoHeadings.isEmpty()) null else {
                "Entirely bold but not a heading style: ${pseudoHeadings.size} paragraph(s) - " +
                    numbers(pseudoHeadings) + ". These are usually headings that were never styled as one."
            },
            if (manual.isEmpty()) null else {
                "Typed-in numbering rather than a real Word list: ${manual.size} paragraph(s) - " + numbers(manual) + "."
            },
            if (empties.isEmpty()) null else {
                "Empty paragraphs used as spacing: ${empties.size} - " + numbers(empties) + "."
            },
        ).joinToString("\n")
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

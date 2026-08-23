package ch.arcticsoft.springchat3.tools

import ch.arcticsoft.springchat3.document.WordDocumentRef
import ch.arcticsoft.springchat3.document.WordDocumentService
import ch.arcticsoft.springchat3.document.WordDocumentWorkspace
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * Creating and editing the active project's Word documents - the only tools
 * in this app with side effects, and deliberately the only ones NOT offered
 * to [ch.arcticsoft.springchat3.agent.ChatAgent.analyzeMessage]'s small
 * tool-selection model. They're attached to the `answer` step's generation
 * model instead (2026-08-23, user's own decision: "analyzeMessage can use
 * tools to read the document. editing a document should be made by answer.").
 * That step's model is the larger one, and its prompt has the user's actual
 * message plus everything the read tools already gathered - which is the
 * context you want behind an irreversible-looking action, rather than a 3B
 * model whose job is only to decide which lookups to run.
 *
 * Per chat turn, not a `@Component` - same reasoning as
 * [WordDocumentReadTool] / [CurrentLocationTool]. Being an [EditingTool] is
 * what keeps it out of the gathering step: that step's tool list is typed
 * `List<GatheringTool>`, so this one cannot be added to it by accident.
 *
 * Safety properties worth keeping true as this grows:
 *  - every edit is scoped to the ACTIVE PROJECT's uploaded Word documents
 *    (see [WordDocumentWorkspace]) - no other project, no PDFs, no linked
 *    Google Docs, nothing outside this app's own storage,
 *  - every edit first takes a one-level undo copy, exposed back to the model
 *    (and so to the user, by asking) as [undoWordDocumentEdit],
 *  - every tool description opens by telling the model to only act on an
 *    explicit request, since a generation model that has these available on
 *    every turn will otherwise "helpfully" tidy a document nobody asked it
 *    to touch,
 *  - every successful edit returns the document's NEW paragraph count and
 *    says the numbering has shifted, because paragraph numbers are
 *    positional (see [ch.arcticsoft.springchat3.document.WordParagraph]) -
 *    that's the agreed alternative to stable anchors, and it only works if
 *    the model is told each time.
 *
 * Paragraph numbers are 1-based here exactly as in [WordDocumentReadTool],
 * and converted to the service's 0-based indexes in this class.
 */
class WordDocumentEditTool(
    private val workspace: WordDocumentWorkspace,
    private val wordDocumentService: WordDocumentService,
    private val projectId: String?,
    private val userMessage: String,
    /**
     * The documents the user has selected in the side panel this turn - a
     * hard scope, not a hint (2026-08-23, user's own report: "I had 0
     * documents selected" and the step still ran against both documents in
     * the project). Every lookup below goes through it, so an unselected
     * document cannot be listed, resolved or changed; with nothing selected
     * there is nothing to edit at all, and [ch.arcticsoft.springchat3.agent.ChatAgent.documentEdit]
     * skips the step before it ever gets here.
     */
    private val selectedDocumentIds: Set<String>,
) : EditingTool {

    /**
     * The guard that stops an edit landing in a document nobody pointed at
     * (2026-08-23, from a real failure: asked to append to "First
     * Document.docx", the model called append_to_word_document with
     * "PID E2E Challenges and Opportunities.docx" instead - the wrong file
     * of the two in the project - and nothing questioned it).
     *
     * Since [selectedDocumentIds] became a hard scope (2026-08-23), every
     * candidate reaching here is already one the user attached to this turn -
     * so what is left to decide is only WHICH of several selected documents
     * an unqualified request meant:
     *  - exactly one document is selected, so there is nothing to get wrong,
     *  - or the message names it (its filename, with or without the .docx,
     *    read case-insensitively).
     *
     * Anything else is a guess, and the tool refuses with an error telling
     * the model to ask which document is meant. A guess that happens to be
     * right costs one clarifying question; a guess that is wrong silently
     * edits a file the user wasn't even thinking about, which is exactly
     * what happened. Note what is deliberately NOT a route any more:
     * "it is selected". With the scope in place that was true of every
     * candidate, so it would have waved through precisely the ambiguous case
     * this guard exists for - two selected documents and a message naming
     * neither. Implemented in code rather than only as prompt guidance: the
     * prompt says it too, but the prompt is advice and this is a rule.
     */
    private fun targetedByUser(ref: WordDocumentRef, candidateCount: Int): Boolean {
        if (candidateCount <= 1) return true
        val stem = ref.filename.substringBeforeLast('.')
        return userMessage.contains(ref.filename, ignoreCase = true) || userMessage.contains(stem, ignoreCase = true)
    }

    private fun withDocument(filename: String, block: (WordDocumentRef) -> String): String {
        if (projectId == null) {
            return """{"error": "No project is selected, so there are no Word documents to edit."}"""
        }
        val candidates = workspace.list(projectId, selectedDocumentIds)
        if (candidates.isEmpty()) {
            return """{"error": "The user has no Word document selected, so there is nothing to change. """ +
                """Ask them to select the document they mean in the side panel."}"""
        }
        val ref = workspace.resolve(projectId, filename, selectedDocumentIds)
            ?: return """{"error": "No selected Word document matches \"$filename\". Use the exact filename - list_word_documents shows what the user has selected."}"""
        if (!targetedByUser(ref, candidates.size)) {
            val names = candidates.joinToString(", ") { "\"${it.filename}\"" }
            return """{"error": "Refusing to change \"${ref.filename}\": the user has several documents selected and named none of them. """ +
                """Ask which one they mean. The selected documents are: $names."}"""
        }
        return try {
            block(ref)
        } catch (e: Exception) {
            """{"error": "Could not edit \"${ref.filename}\": ${e.message}"}"""
        }
    }

    private fun edited(ref: WordDocumentRef, what: String, count: Int?): String =
        if (count == null) {
            """{"error": "\"${ref.filename}\" could not be changed."}"""
        } else {
            "$what in \"${ref.filename}\". It now has $count paragraphs, and paragraph numbers may have shifted - " +
                "read it again before making another change that refers to a paragraph number."
        }

    @Tool(
        name = "create_word_document",
        description = "Create a new Word document in the user's current project. Only call this when the user " +
            "explicitly asked for a document to be created. The content is plain text, one paragraph per line; " +
            "a line starting with \"# \", \"## \" or \"### \" becomes a heading, and a line starting with \"- \" " +
            "becomes a bullet point.",
    )
    fun createWordDocument(
        @ToolParam(description = "Filename for the new document, e.g. \"Meeting notes.docx\"")
        filename: String,
        @ToolParam(description = "The document's content, one paragraph per line, with # / ## / ### headings and - bullets")
        content: String,
    ): String {
        if (projectId == null) return """{"error": "No project is selected, so there is nowhere to create a document."}"""
        if (content.isBlank()) return """{"error": "The content is empty - there is nothing to write into the document."}"""
        return try {
            val ref = workspace.create(projectId, filename, content)
            val count = workspace.paragraphCount(ref) ?: 0
            "Created \"${ref.filename}\" with $count paragraphs. It is now listed under Working Documents."
        } catch (e: Exception) {
            """{"error": "Could not create \"$filename\": ${e.message}"}"""
        }
    }

    @Tool(
        name = "append_to_word_document",
        description = "Add paragraphs to the end of an existing Word document. Only call this when the user " +
            "explicitly asked for something to be added. Same one-paragraph-per-line format as " +
            "create_word_document, including # headings and - bullets.",
    )
    fun appendToWordDocument(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "The text to append, one paragraph per line")
        text: String,
    ): String = withDocument(filename) { ref ->
        if (text.isBlank()) {
            """{"error": "The text is empty - there is nothing to append."}"""
        } else {
            edited(ref, "Appended", workspace.applyEdit(ref) { wordDocumentService.append(it, text) })
        }
    }

    @Tool(
        name = "insert_paragraphs_into_word_document",
        description = "Insert one or more paragraphs into a Word document, after a given paragraph number. " +
            "Use 0 to insert at the very beginning. Only call this when the user explicitly asked for " +
            "something to be inserted. Read the document first so you know the right paragraph number.",
    )
    fun insertParagraphsIntoWordDocument(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "Insert after this paragraph number (1 = after the first paragraph, 0 = at the very beginning)")
        afterParagraph: Int,
        @ToolParam(description = "The text to insert, one paragraph per line")
        text: String,
    ): String = withDocument(filename) { ref ->
        if (text.isBlank()) {
            """{"error": "The text is empty - there is nothing to insert."}"""
        } else {
            val count = workspace.applyEdit(ref) { wordDocumentService.insertAfter(it, afterParagraph - 1, text) }
            edited(ref, "Inserted after paragraph $afterParagraph", count)
        }
    }

    @Tool(
        name = "replace_paragraph_in_word_document",
        description = "Replace the whole text of one paragraph in a Word document, keeping its style. Only " +
            "call this when the user explicitly asked for a change. Read the document first so you are " +
            "replacing the paragraph you think you are.",
    )
    fun replaceParagraphInWordDocument(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "Which paragraph to replace, counting from 1")
        paragraphNumber: Int,
        @ToolParam(description = "The paragraph's new text")
        text: String,
    ): String = withDocument(filename) { ref ->
        val count = workspace.applyEdit(ref) { wordDocumentService.replaceParagraph(it, paragraphNumber - 1, text) }
        edited(ref, "Replaced paragraph $paragraphNumber", count)
    }

    @Tool(
        name = "delete_paragraph_from_word_document",
        description = "Delete one paragraph from a Word document. Only call this when the user explicitly " +
            "asked for something to be removed. Read the document first to confirm the paragraph number.",
    )
    fun deleteParagraphFromWordDocument(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "Which paragraph to delete, counting from 1")
        paragraphNumber: Int,
    ): String = withDocument(filename) { ref ->
        val count = workspace.applyEdit(ref) { wordDocumentService.deleteParagraph(it, paragraphNumber - 1) }
        edited(ref, "Deleted paragraph $paragraphNumber", count)
    }

    @Tool(
        name = "replace_text_in_word_document",
        description = "Find and replace text throughout a Word document - the right tool for a rename or a " +
            "wording change that appears in several places. Only call this when the user explicitly asked " +
            "for the change. The search is exact and case-sensitive.",
    )
    fun replaceTextInWordDocument(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "The exact text to find")
        find: String,
        @ToolParam(description = "What to replace it with")
        replacement: String,
        @ToolParam(description = "true to replace every occurrence, false to replace only the first one")
        replaceAll: Boolean,
    ): String = withDocument(filename) { ref ->
        var changedParagraphs = 0
        // The service returns null bytes when nothing matched, which
        // applyEdit treats as "don't write, don't touch the undo copy".
        val count = workspace.applyEdit(ref) { bytes ->
            val (updated, changed) = wordDocumentService.replaceText(bytes, find, replacement, replaceAll)
            changedParagraphs = changed
            updated
        }
        if (count == null || changedParagraphs == 0) {
            "\"${ref.filename}\" does not contain \"$find\", so nothing was changed."
        } else {
            edited(ref, "Replaced \"$find\" in $changedParagraphs paragraph(s)", count)
        }
    }

    @Tool(
        name = "undo_word_document_edit",
        description = "Undo the most recent change made to a Word document, restoring it to how it was before " +
            "that change. Only one level of undo is kept, and it cannot itself be undone. Call this when the " +
            "user says an edit was wrong or asks to revert it.",
    )
    fun undoWordDocumentEdit(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
    ): String = withDocument(filename) { ref ->
        val count = workspace.undo(ref)
        if (count == null) {
            """{"error": "There is no saved previous version of \"${ref.filename}\" to restore."}"""
        } else {
            "Restored \"${ref.filename}\" to its previous version - it now has $count paragraphs."
        }
    }
}

package ch.arcticsoft.springchat3.tools

import ch.arcticsoft.springchat3.document.WordDocumentRef
import ch.arcticsoft.springchat3.document.WordDocumentService
import ch.arcticsoft.springchat3.document.WordDocumentWorkspace
import ch.arcticsoft.springchat3.document.WordStyle
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.util.concurrent.ConcurrentHashMap

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
private val COLLECTIVE_REFERENCE = Regex(
    // Word-bounded: "all" must not match "small", and "beide" is a whole word
    // in the German phrasings this app actually sees.
    "\\b(both|all|each|every|either|them)\\b|\\b(alle|beide[nsr]?|jede[nsmr]?)\\b",
    RegexOption.IGNORE_CASE,
)

class WordDocumentEditTool(
    private val workspace: WordDocumentWorkspace,
    private val wordDocumentService: WordDocumentService,
    private val spaceId: String?,
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
    /**
     * The documents THIS caller has unlocked for editing (2026-08-25) - a
     * second hard scope, and the one that is off by default.
     *
     * Deliberately per user rather than a flag on the document: a shared flag
     * would let one person's unlock surprise another, who attaches the file
     * to ask a question and gets an edit they never intended. Who may change
     * a space's contents at all is a different question, already answered by
     * [ch.arcticsoft.springchat3.project.SpaceRole].
     */
    private val editableDocumentIds: Set<String>,
    /**
     * The assistant's previous reply in this chat session, or null when
     * there is none (2026-08-28) - the content [saveAnswerAsWordDocument]
     * writes. Passed in rather than reachable from here: this class knows
     * nothing about sessions, and the reply has already been ownership-
     * checked by the time it arrives (see
     * [ch.arcticsoft.springchat3.chat.ChatHistoryStore.lastAssistantText]).
     */
    private val previousAnswer: String? = null,
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
        // A collective reference names the whole selection, and refusing it
        // was a dead end (2026-08-29, from a real session): the app asked
        // "which document should I modify?", the user answered "do it on
        // both", and this guard refused every call - so the correct answer to
        // the app's OWN question could never succeed. Only consulted when
        // more than one document is selected, which is exactly when "both"
        // and "all" are unambiguous rather than vague.
        if (COLLECTIVE_REFERENCE.containsMatchIn(userMessage)) return true
        val stem = ref.filename.substringBeforeLast('.')
        return userMessage.contains(ref.filename, ignoreCase = true) || userMessage.contains(stem, ignoreCase = true)
    }

    /**
     * Every refusal already issued this turn, so the same one is not handed
     * back over and over (2026-08-29). A real turn spent roughly 40 of its 67
     * seconds on SIX identical "the user has several documents selected"
     * refusals: [once] guards the write path, but a refusal happens in
     * [withDocument] before that, so nothing recorded it and the model simply
     * tried again. Repeating an unchanged error is what invites the retry;
     * saying "you already asked, the answer has not changed" is what stops it.
     */
    private val refusals = ConcurrentHashMap.newKeySet<String>()

    private fun refuse(reason: String, message: String): String =
        if (refusals.add(reason)) {
            message
        } else {
            """{"error": "This was already refused earlier in this turn and the answer has not changed. Stop """ +
                """retrying it and tell the user plainly what you need from them."}"""
        }

    /**
     * Every mutating call already applied by this tool object, as
     * "operation|arguments" (2026-08-28). Lives on the instance because the
     * instance is per chat turn, which is exactly the window that has to be
     * protected.
     *
     * **The threat is a retry, not a confused model.** Embabel cancels an
     * LLM call that exceeds its timeout and retries it - up to
     * `embabel.agent.platform.llm-operations.data-binding.max-attempts`
     * times - and a retry re-runs the tool loop from the start with the same
     * tool objects. A real run (2026-08-28: "make each of the stories
     * longer, around 300 words each") timed out mid-generation and retried
     * nine times. Nothing was written that time because the model never got
     * as far as an edit, but "append this paragraph" applied twice is a
     * silently corrupted document, and the timeout makes that replay routine
     * rather than hypothetical.
     *
     * Concurrent because retries do not all run on the calling thread (see
     * [ch.arcticsoft.springchat3.agent.ToolCallProgressBridge]'s doc comment
     * for how that was learned the hard way).
     */
    private val applied = ConcurrentHashMap.newKeySet<String>()

    /**
     * Refuses [operation] if this exact call already ran in this turn, and
     * otherwise runs it - see [applied].
     *
     * Deliberately keyed on the ARGUMENTS too: appending "and then he left"
     * twice is a replay, appending two different paragraphs is two edits the
     * user may well have asked for. The reply the model gets says the change
     * is already applied, so the honest outcome ("it is done") is also the
     * one that stops it looping.
     *
     * **Not an `{"error": ...}`**, deliberately: the change the model asked
     * for IS in the document, so an error envelope would show as a failed
     * call in the turn's trace and tell [ch.arcticsoft.springchat3.agent.ChatAgent.answer]
     * to report a failure to the user. Nothing failed - the second attempt
     * was simply not needed.
     */
    private fun once(operation: String, block: () -> String): String =
        if (applied.add(operation)) {
            block()
        } else {
            "That exact change was already applied earlier in this turn and is saved. Nothing more was done."
        }

    private fun withDocument(filename: String, block: (WordDocumentRef) -> String): String {
        if (spaceId == null) {
            return """{"error": "No project is selected, so there are no Word documents to edit."}"""
        }
        val candidates = workspace.list(spaceId, selectedDocumentIds)
        if (candidates.isEmpty()) {
            return refuse(
                "nothing-selected",
                """{"error": "The user has no Word document selected, so there is nothing to change. """ +
                    """Ask them to select the document they mean in the side panel."}""",
            )
        }
        val ref = workspace.resolve(spaceId, filename, selectedDocumentIds)
            ?: return refuse(
                "no-match|$filename",
                """{"error": "No selected Word document matches \"$filename\". Use the exact filename - list_word_documents shows what the user has selected."}""",
            )
        // Checked after resolve so the refusal can name the document, and
        // before targetedByUser so a locked file is reported as locked rather
        // than as ambiguous. Every tool method funnels through here, which is
        // why this is the only place the lock is enforced.
        if (ref.documentId !in editableDocumentIds) {
            return refuse(
                "locked|${ref.filename}",
                """{"error": "\"${ref.filename}\" is locked. The user has not unlocked it for editing - """ +
                    """tell them they can unlock it with the padlock on the document's card in the side panel. """ +
                    """Do not attempt any other way of changing it."}""",
            )
        }
        if (!targetedByUser(ref, candidates.size)) {
            val names = candidates.joinToString(", ") { "\"${it.filename}\"" }
            return refuse(
                "ambiguous|${ref.filename}",
                """{"error": "Refusing to change \"${ref.filename}\": the user has several documents selected and named none of them. """ +
                    """Ask which one they mean, or have them say \"both\" or \"all\". The selected documents are: $names."}""",
            )
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
        if (spaceId == null) return """{"error": "No project is selected, so there is nowhere to create a document."}"""
        if (content.isBlank()) return """{"error": "The content is empty - there is nothing to write into the document."}"""
        // Guarded like every edit: a replayed create makes a SECOND document.
        return once("create|$filename|$content") {
            try {
                val ref = workspace.create(spaceId, filename, content)
                val count = workspace.paragraphCount(ref) ?: 0
                "Created \"${ref.filename}\" with $count paragraphs. It is now listed under Working Documents."
            } catch (e: Exception) {
                """{"error": "Could not create \"$filename\": ${e.message}"}"""
            }
        }
    }

    /**
     * "Save the summary in a new document" (2026-08-28, from a real failure
     * where exactly that request produced "I cannot create a new document"
     * and zero tool calls).
     *
     * **The content is not a parameter.** The model chooses only whether to
     * call this and what to call the file; the text comes from
     * [previousAnswer] verbatim. That is the whole point:
     *  - "save the summary" means the text the user just read, not a fresh
     *    paraphrase of it - and a model asked to re-emit a long reply
     *    through a tool argument will shorten, reword or truncate it,
     *  - the local models this app runs are exactly the ones that do that
     *    worst, and a several-hundred-word argument is where they fail,
     *  - it cannot save anything the user has not already been shown, which
     *    is a much easier property to reason about than "whatever the model
     *    decided to put in a document".
     *
     * A missing previous answer is refused HERE rather than by leaving the
     * tool unattached: a tool the model cannot see produces a prose "I
     * can't", which reaches nobody (see
     * [ch.arcticsoft.springchat3.agent.ChatAgent.documentEdit] - only tool
     * results cross into the reply). An error return is a result.
     */
    @Tool(
        name = "save_answer_as_word_document",
        description = "Save your previous reply in this chat as a new Word document, exactly as it was written. " +
            "Call this when the user asks to save, keep or write down the answer, the summary or what you just " +
            "told them - do NOT use create_word_document and retype it. Takes only a filename; the content is " +
            "the earlier reply itself.",
    )
    fun saveAnswerAsWordDocument(
        @ToolParam(description = "Filename for the new document, e.g. \"Summary.docx\"")
        filename: String,
    ): String {
        if (spaceId == null) return """{"error": "No project is selected, so there is nowhere to create a document."}"""
        // The rule, not the advice (2026-08-29). The prompt hint that
        // advertises this tool is now gated on the same check, but a hint is
        // advice and this is what actually refuses - the same split
        // [targetedByUser] already makes, and for the same reason: the
        // refusal has to be a tool RESULT, because prose from this step
        // reaches nobody.
        //
        // What it stops: "edit greeks.docx. make the stories longer" was
        // answered by saving the previous turn's apology as a 13-paragraph
        // "Summary.docx" while greeks.docx went untouched. Nothing in that
        // message asks for a reply to be saved.
        if (!SaveAnswerIntent.isAskedFor(userMessage)) {
            return """{"error": "This message does not ask for the previous reply to be saved, so nothing was """ +
                """saved. If a document was to be CHANGED, use the editing tools on that document instead. If the """ +
                """user really did want the earlier reply kept, they need to say so - for example \"save the answer """ +
                """as Notes.docx\"."}"""
        }
        val content = previousAnswer?.trim()
        if (content.isNullOrBlank()) {
            return """{"error": "There is no earlier reply in this chat to save - this is the first message of the """ +
                """session. Answer the user first; they can then ask again to save that answer."}"""
        }
        return once("saveAnswer|$filename") {
            try {
                val ref = workspace.create(spaceId, filename, content)
                val count = workspace.paragraphCount(ref) ?: 0
                "Saved the previous reply as \"${ref.filename}\" ($count paragraphs). It is now listed under Working Documents."
            } catch (e: Exception) {
                """{"error": "Could not create \"$filename\": ${e.message}"}"""
            }
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
        once("append|${ref.filename}|$text") {
            if (text.isBlank()) {
                """{"error": "The text is empty - there is nothing to append."}"""
            } else {
                edited(ref, "Appended", workspace.applyEdit(ref) { wordDocumentService.append(it, text) })
            }
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
        once("insert|${ref.filename}|$afterParagraph|$text") {
            if (text.isBlank()) {
                """{"error": "The text is empty - there is nothing to insert."}"""
            } else {
                val count = workspace.applyEdit(ref) { wordDocumentService.insertAfter(it, afterParagraph - 1, text) }
                edited(ref, "Inserted after paragraph $afterParagraph", count)
            }
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
        once("replaceParagraph|${ref.filename}|$paragraphNumber|$text") {
            val count = workspace.applyEdit(ref) { wordDocumentService.replaceParagraph(it, paragraphNumber - 1, text) }
            edited(ref, "Replaced paragraph $paragraphNumber", count)
        }
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
        once("deleteParagraph|${ref.filename}|$paragraphNumber") {
            val count = workspace.applyEdit(ref) { wordDocumentService.deleteParagraph(it, paragraphNumber - 1) }
            edited(ref, "Deleted paragraph $paragraphNumber", count)
        }
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
        once("replaceText|${ref.filename}|$find|$replacement|$replaceAll") {
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
    }

    /**
     * Resolves whatever the user called a style to an id this document
     * actually defines - exact id, then id ignoring case and spaces, then
     * display name. Necessary rather than tidy: `pStyle` takes an id, and
     * applying an id the document does not define DOES NOT FAIL - Word
     * silently renders the paragraph as Normal, which from the outside is
     * indistinguishable from the tool having done nothing. A German-authored
     * document may define no "Heading1" at all, so "make these Heading 1"
     * has to be looked up, never assumed.
     */
    private fun resolveStyleId(styles: List<WordStyle>, requested: String): String? {
        fun normalized(value: String) = value.lowercase().replace(" ", "")
        return styles.firstOrNull { it.styleId == requested }?.styleId
            ?: styles.firstOrNull { normalized(it.styleId) == normalized(requested) }?.styleId
            // ?.let rather than a null check plus a smart cast: the property
            // comes from another file, and a defensive form costs nothing here.
            ?: styles.firstOrNull { style -> style.name?.let { normalized(it) == normalized(requested) } == true }?.styleId
    }

    /**
     * Reports a formatting change WITHOUT the "paragraph numbers may have
     * shifted" warning [edited] carries: formatting never moves a paragraph,
     * and telling the model to re-read after every font tweak would cost a
     * round trip per change for nothing.
     */
    private fun formatted(ref: WordDocumentRef, what: String, extra: String = ""): String =
        "$what in \"${ref.filename}\".$extra"

    /**
     * The agreed behaviour for a font change that will not take (2026-08-24):
     * report the conflict, never strip it. Direct run formatting beats both
     * docDefaults and a style, so a document that arrived by paste overrides
     * everything - and stripping it automatically would silently discard
     * somebody's deliberate local formatting, which is worse than a change
     * that did not apply. The count is the answer to "why did nothing
     * happen?", and clearing is offered rather than done.
     */
    private fun directFormattingConflict(ref: WordDocumentRef): String {
        val report = workspace.formatting(ref) ?: return ""
        val overriding = report.paragraphs.count { it.directlyFormattedRuns > 0 }
        if (overriding == 0) return ""
        return " But $overriding of ${report.paragraphs.size} paragraph(s) set their own formatting directly, " +
            "which overrides this and will not change: say so, and offer to clear it with " +
            "clear_direct_formatting. Do not clear anything unless the user asks you to."
    }

    @Tool(
        name = "set_paragraph_style",
        description = "Apply a Word paragraph style (e.g. \"Heading 1\", \"Normal\", \"Quote\") to a range of " +
            "paragraphs. This is the right fix when text LOOKS like a heading but is only bold body text: a real " +
            "style makes the navigation pane, tables of contents and later restyling work. Call list_word_styles " +
            "first to see what this document defines. Paragraph numbers count from 1; use the same number twice " +
            "for a single paragraph.",
    )
    fun setParagraphStyle(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "First paragraph to restyle, counting from 1")
        fromParagraph: Int,
        @ToolParam(description = "Last paragraph to restyle, counting from 1 (same as the first for one paragraph)")
        toParagraph: Int,
        @ToolParam(description = "The style's id or display name, e.g. \"Heading1\" or \"Heading 1\"")
        style: String,
    ): String = withDocument(filename) { ref ->
        val styles = workspace.styles(ref)
        val styleId = resolveStyleId(styles, style)
        if (styleId == null) {
            val available = styles.take(30).joinToString(", ") { it.name ?: it.styleId }
            """{"error": "\"${ref.filename}\" does not define a style called \"$style\", and applying one it does """ +
                """not define would silently do nothing. Available styles: $available."}"""
        } else {
            once("setParagraphStyle|${ref.filename}|$fromParagraph|$toParagraph|$styleId") {
                var changedParagraphs = 0
                val count = workspace.applyEdit(ref) { bytes ->
                    val (updated, changed) = wordDocumentService.setParagraphStyle(
                        bytes, fromParagraph - 1, toParagraph - 1, styleId,
                    )
                    changedParagraphs = changed
                    updated
                }
                if (count == null || changedParagraphs == 0) {
                    "Nothing changed: paragraphs $fromParagraph-$toParagraph of \"${ref.filename}\" already use " +
                        "\"$style\", or that range does not exist."
                } else {
                    formatted(ref, "Applied \"$style\" to $changedParagraphs paragraph(s)")
                }
            }
        }
    }

    @Tool(
        name = "set_document_font",
        description = "Set the font and/or size for the WHOLE document at once, by writing its document " +
            "defaults - what Word's \"Change Styles - Fonts\" does. Use this for \"make the document Calibri 11\". " +
            "Give only the font name, only the size, or both. The result says if some paragraphs override it.",
    )
    fun setDocumentFont(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "Font name, e.g. \"Calibri\". Leave out to keep the current font.", required = false)
        fontName: String?,
        @ToolParam(description = "Font size in points, e.g. 11. Leave out to keep the current size.", required = false)
        sizePt: Int?,
    ): String = withDocument(filename) { ref ->
        if (fontName.isNullOrBlank() && sizePt == null) {
            """{"error": "Neither a font name nor a size was given, so there is nothing to set."}"""
        } else {
            once("setDocumentFont|${ref.filename}|$fontName|$sizePt") {
                val count = workspace.applyEdit(ref) { bytes ->
                    wordDocumentService.setDocumentFont(bytes, fontName?.ifBlank { null }, sizePt)
                }
                if (count == null) {
                    """{"error": "\"${ref.filename}\" has no styles part, so it has no document defaults to set. """ +
                        """Set the font on the paragraphs themselves with set_paragraph_font instead."}"""
                } else {
                    val what = listOfNotNull(fontName?.ifBlank { null }, sizePt?.let { "${it}pt" }).joinToString(" ")
                    formatted(ref, "Set the document default font to $what", directFormattingConflict(ref))
                }
            }
        }
    }

    @Tool(
        name = "set_style_font",
        description = "Set the font, size, boldness and/or colour of one named style, so every paragraph using " +
            "that style changes together and stays consistent afterwards - e.g. \"Heading 1 should be Georgia 16 " +
            "bold\". Prefer this over set_paragraph_font whenever the user is describing a KIND of text rather " +
            "than particular paragraphs. Give only the properties being changed.",
    )
    fun setStyleFont(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "The style's id or display name, e.g. \"Heading1\" or \"Heading 1\"")
        style: String,
        @ToolParam(description = "Font name, e.g. \"Georgia\". Leave out to keep it.", required = false)
        fontName: String?,
        @ToolParam(description = "Font size in points, e.g. 16. Leave out to keep it.", required = false)
        sizePt: Int?,
        @ToolParam(description = "true for bold, false for not bold. Leave out to keep it.", required = false)
        bold: Boolean?,
        @ToolParam(description = "Colour as a 6-digit hex code without the #, e.g. \"1F4E79\". Leave out to keep it.", required = false)
        color: String?,
    ): String = withDocument(filename) { ref ->
        val styles = workspace.styles(ref)
        val styleId = resolveStyleId(styles, style)
        if (styleId == null) {
            val available = styles.take(30).joinToString(", ") { it.name ?: it.styleId }
            """{"error": "\"${ref.filename}\" does not define a style called \"$style\". Available styles: $available."}"""
        } else {
            once("setStyleFont|${ref.filename}|$styleId|$fontName|$sizePt|$bold|$color") {
                val count = workspace.applyEdit(ref) { bytes ->
                    wordDocumentService.setStyleFont(
                        bytes, styleId, fontName?.ifBlank { null }, sizePt, bold, color?.ifBlank { null },
                    )
                }
                if (count == null) {
                    """{"error": "Nothing was set on \"$style\" - no font, size, boldness or colour was given."}"""
                } else {
                    formatted(ref, "Restyled \"$style\"", directFormattingConflict(ref))
                }
            }
        }
    }

    @Tool(
        name = "set_paragraph_font",
        description = "Set the font, size, boldness, italics and/or colour directly on a range of paragraphs - " +
            "for a title page, a caption or one callout. Use set_style_font or set_document_font instead when " +
            "the change is about a kind of text or the whole document: what this writes overrides both of those " +
            "afterwards. Paragraph numbers count from 1.",
    )
    fun setParagraphFont(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "First paragraph to change, counting from 1")
        fromParagraph: Int,
        @ToolParam(description = "Last paragraph to change, counting from 1 (same as the first for one paragraph)")
        toParagraph: Int,
        @ToolParam(description = "Font name, e.g. \"Calibri\". Leave out to keep it.", required = false)
        fontName: String?,
        @ToolParam(description = "Font size in points, e.g. 11. Leave out to keep it.", required = false)
        sizePt: Int?,
        @ToolParam(description = "true for bold, false for not bold. Leave out to keep it.", required = false)
        bold: Boolean?,
        @ToolParam(description = "true for italic, false for not italic. Leave out to keep it.", required = false)
        italic: Boolean?,
        @ToolParam(description = "Colour as a 6-digit hex code without the #, e.g. \"C00000\". Leave out to keep it.", required = false)
        color: String?,
    ): String = withDocument(filename) { ref ->
        once("setParagraphFont|${ref.filename}|$fromParagraph|$toParagraph|$fontName|$sizePt|$bold|$italic|$color") {
            var changedParagraphs = 0
            val count = workspace.applyEdit(ref) { bytes ->
                val (updated, changed) = wordDocumentService.setParagraphFont(
                    bytes, fromParagraph - 1, toParagraph - 1,
                    fontName?.ifBlank { null }, sizePt, bold, italic, color?.ifBlank { null },
                )
                changedParagraphs = changed
                updated
            }
            if (count == null || changedParagraphs == 0) {
                "Nothing changed: either no property was given, or paragraphs $fromParagraph-$toParagraph of " +
                    "\"${ref.filename}\" do not exist or hold no text."
            } else {
                formatted(ref, "Reformatted $changedParagraphs paragraph(s)")
            }
        }
    }

    @Tool(
        name = "clear_direct_formatting",
        description = "Remove font, size, bold, italic and colour set directly on a range of paragraphs, so they " +
            "fall back to their style and the document defaults. This is what makes a document-wide font change " +
            "actually take effect on paragraphs that were overriding it. Only call this when the user has asked " +
            "for it - it discards formatting somebody may have applied deliberately. Paragraph numbers count from 1.",
    )
    fun clearDirectFormatting(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "First paragraph to clear, counting from 1")
        fromParagraph: Int,
        @ToolParam(description = "Last paragraph to clear, counting from 1 (use 1 and a large number for the whole document)")
        toParagraph: Int,
    ): String = withDocument(filename) { ref ->
        once("clearDirectFormatting|${ref.filename}|$fromParagraph|$toParagraph") {
            var changedParagraphs = 0
            val count = workspace.applyEdit(ref) { bytes ->
                val (updated, changed) = wordDocumentService.clearDirectFormatting(bytes, fromParagraph - 1, toParagraph - 1)
                changedParagraphs = changed
                updated
            }
            if (count == null || changedParagraphs == 0) {
                "Nothing changed: paragraphs $fromParagraph-$toParagraph of \"${ref.filename}\" carry no direct " +
                    "formatting, or that range does not exist."
            } else {
                formatted(ref, "Cleared direct formatting from $changedParagraphs paragraph(s)")
            }
        }
    }

    @Tool(
        name = "set_paragraph_spacing",
        description = "Set the space above and below paragraphs, and the line spacing WITHIN them, for a range " +
            "of paragraphs. This is what \"less space between the lines\", \"tighter\" or \"more room between " +
            "paragraphs\" mean. Line spacing is a multiple: 1 is single, 1.5 is one-and-a-half, 2 is double. " +
            "Give only the values being changed. Prefer set_style_paragraph_format when the whole document or a " +
            "whole kind of text should change. Paragraph numbers count from 1.",
    )
    fun setParagraphSpacing(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "First paragraph to change, counting from 1")
        fromParagraph: Int,
        @ToolParam(description = "Last paragraph to change, counting from 1 (same as the first for one paragraph)")
        toParagraph: Int,
        @ToolParam(description = "Space ABOVE each paragraph in points, e.g. 6. Leave out to keep it.", required = false)
        spaceBeforePt: Double?,
        @ToolParam(description = "Space BELOW each paragraph in points, e.g. 6. Leave out to keep it.", required = false)
        spaceAfterPt: Double?,
        @ToolParam(description = "Line spacing as a multiple: 1 single, 1.15, 1.5, 2 double. Leave out to keep it.", required = false)
        lineSpacing: Double?,
    ): String = withDocument(filename) { ref ->
        once("spacing|${ref.filename}|$fromParagraph|$toParagraph|$spaceBeforePt|$spaceAfterPt|$lineSpacing") {
            var changedParagraphs = 0
            val count = workspace.applyEdit(ref) { bytes ->
                val (updated, changed) = wordDocumentService.setParagraphSpacing(
                    bytes, fromParagraph - 1, toParagraph - 1, spaceBeforePt, spaceAfterPt, lineSpacing,
                )
                changedParagraphs = changed
                updated
            }
            if (count == null || changedParagraphs == 0) {
                "Nothing changed: either no spacing value was given, or paragraphs $fromParagraph-$toParagraph of " +
                    "\"${ref.filename}\" do not exist."
            } else {
                formatted(ref, "Set the spacing on $changedParagraphs paragraph(s)")
            }
        }
    }

    @Tool(
        name = "set_paragraph_alignment",
        description = "Align a range of paragraphs left, center, right or justified. Paragraph numbers count from 1.",
    )
    fun setParagraphAlignment(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "First paragraph to align, counting from 1")
        fromParagraph: Int,
        @ToolParam(description = "Last paragraph to align, counting from 1")
        toParagraph: Int,
        @ToolParam(description = "One of: left, center, right, justified")
        alignment: String,
    ): String = withDocument(filename) { ref ->
        val value = wordDocumentService.alignmentValue(alignment)
        if (value == null) {
            """{"error": "\"$alignment\" is not an alignment. Use left, center, right or justified."}"""
        } else {
            once("align|${ref.filename}|$fromParagraph|$toParagraph|$alignment") {
                var changedParagraphs = 0
                val count = workspace.applyEdit(ref) { bytes ->
                    val (updated, changed) =
                        wordDocumentService.setParagraphAlignment(bytes, fromParagraph - 1, toParagraph - 1, value)
                    changedParagraphs = changed
                    updated
                }
                if (count == null || changedParagraphs == 0) {
                    "Nothing changed: paragraphs $fromParagraph-$toParagraph of \"${ref.filename}\" do not exist."
                } else {
                    formatted(ref, "Aligned $changedParagraphs paragraph(s) $alignment")
                }
            }
        }
    }

    @Tool(
        name = "set_paragraph_indent",
        description = "Indent a range of paragraphs, in centimetres from the left margin, the right margin, or " +
            "for the first line only. Use 0 to remove an indent. Paragraph numbers count from 1.",
    )
    fun setParagraphIndent(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "First paragraph to indent, counting from 1")
        fromParagraph: Int,
        @ToolParam(description = "Last paragraph to indent, counting from 1")
        toParagraph: Int,
        @ToolParam(description = "Left indent in centimetres, e.g. 1.25. Leave out to keep it.", required = false)
        leftCm: Double?,
        @ToolParam(description = "Right indent in centimetres. Leave out to keep it.", required = false)
        rightCm: Double?,
        @ToolParam(description = "First-line indent in centimetres. Leave out to keep it.", required = false)
        firstLineCm: Double?,
    ): String = withDocument(filename) { ref ->
        once("indent|${ref.filename}|$fromParagraph|$toParagraph|$leftCm|$rightCm|$firstLineCm") {
            var changedParagraphs = 0
            val count = workspace.applyEdit(ref) { bytes ->
                val (updated, changed) = wordDocumentService.setParagraphIndent(
                    bytes, fromParagraph - 1, toParagraph - 1, leftCm, rightCm, firstLineCm,
                )
                changedParagraphs = changed
                updated
            }
            if (count == null || changedParagraphs == 0) {
                "Nothing changed: either no indent was given, or paragraphs $fromParagraph-$toParagraph of " +
                    "\"${ref.filename}\" do not exist."
            } else {
                formatted(ref, "Indented $changedParagraphs paragraph(s)")
            }
        }
    }

    @Tool(
        name = "set_style_paragraph_format",
        description = "Set spacing, line spacing and/or alignment on a named STYLE, so every paragraph using it " +
            "changes together and anything written later stays consistent. This is the right tool for \"less " +
            "space between the lines\" across a whole document: set it on \"Normal\". Line spacing is a " +
            "multiple (1 single, 1.5, 2 double). Call list_word_styles first to see what this document defines.",
    )
    fun setStyleParagraphFormat(
        @ToolParam(description = "The document's filename, e.g. \"Spec v2.docx\"")
        filename: String,
        @ToolParam(description = "The style's id or display name, e.g. \"Normal\" or \"Heading 1\"")
        style: String,
        @ToolParam(description = "Space above each paragraph in points. Leave out to keep it.", required = false)
        spaceBeforePt: Double?,
        @ToolParam(description = "Space below each paragraph in points. Leave out to keep it.", required = false)
        spaceAfterPt: Double?,
        @ToolParam(description = "Line spacing as a multiple: 1 single, 1.15, 1.5, 2 double. Leave out to keep it.", required = false)
        lineSpacing: Double?,
        @ToolParam(description = "One of: left, center, right, justified. Leave out to keep it.", required = false)
        alignment: String?,
    ): String = withDocument(filename) { ref ->
        val styles = workspace.styles(ref)
        val styleId = resolveStyleId(styles, style)
        val value = alignment?.takeIf { it.isNotBlank() }?.let { wordDocumentService.alignmentValue(it) }
        when {
            styleId == null -> {
                val available = styles.take(30).joinToString(", ") { it.name ?: it.styleId }
                """{"error": "\"${ref.filename}\" does not define a style called \"$style\". Available styles: $available."}"""
            }
            !alignment.isNullOrBlank() && value == null ->
                """{"error": "\"$alignment\" is not an alignment. Use left, center, right or justified."}"""
            else -> once("styleFormat|${ref.filename}|$styleId|$spaceBeforePt|$spaceAfterPt|$lineSpacing|$alignment") {
                val count = workspace.applyEdit(ref) { bytes ->
                    wordDocumentService.setStyleParagraphFormat(
                        bytes, styleId, spaceBeforePt, spaceAfterPt, lineSpacing, value,
                    )
                }
                if (count == null) {
                    """{"error": "Nothing was set on \"$style\" - no spacing, line spacing or alignment was given."}"""
                } else {
                    // Deliberately NOT directFormattingConflict(ref): that
                    // counts RUN-level overrides (rFonts/sz/b/i/color), which
                    // have no bearing on spacing or alignment. What would
                    // override this is a paragraph carrying its own
                    // pPr/spacing, and nothing measures that yet - so say
                    // nothing rather than report the wrong number.
                    formatted(ref, "Set the paragraph format of \"$style\"")
                }
            }
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

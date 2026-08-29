package ch.arcticsoft.springchat3.agent

import ch.arcticsoft.springchat3.document.DocumentIndex
import ch.arcticsoft.springchat3.document.DocumentStore
import ch.arcticsoft.springchat3.document.DocumentStructure
import ch.arcticsoft.springchat3.document.DocumentStructureStore
import ch.arcticsoft.springchat3.document.StructureNode
import ch.arcticsoft.springchat3.document.WordDocumentService
import ch.arcticsoft.springchat3.document.WordDocumentWorkspace
import ch.arcticsoft.springchat3.settings.AppSettingsStore
import ch.arcticsoft.springchat3.settings.ModelRoleKeys
import ch.arcticsoft.springchat3.tools.ChatTool
import ch.arcticsoft.springchat3.tools.ChatToolRegistry
import ch.arcticsoft.springchat3.tools.CurrentLocationTool
import ch.arcticsoft.springchat3.tools.GatheringTool
import ch.arcticsoft.springchat3.tools.GeoTool
import ch.arcticsoft.springchat3.tools.SaveAnswerIntent
import ch.arcticsoft.springchat3.tools.WordDocumentEditTool
import ch.arcticsoft.springchat3.tools.WordDocumentReadTool
import ch.arcticsoft.springchat3.web.ChatController
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.beans.factory.annotation.Value
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.common.ai.model.LlmOptions
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration

@Agent(description = "Answers a chat message: call whatever tools are needed, then answer in one LLM call from the raw results")
class ChatAgent(
    private val geoTool: GeoTool,
    private val chatToolRegistry: ChatToolRegistry,
    private val toolCallBridge: ToolCallProgressBridge,
    private val progressBus: ChatProgressBus,
    private val documentStore: DocumentStore,
    private val documentIndex: DocumentIndex,
    private val documentStructureStore: DocumentStructureStore,
    private val wordDocumentWorkspace: WordDocumentWorkspace,
    private val wordDocumentService: WordDocumentService,
    private val appSettingsStore: AppSettingsStore,
    @Value("\${embabel.models.llms.generation}") private val generationDefaultModel: String,
    @Value("\${embabel.models.llms.document-search-strategy}") private val documentSearchStrategyDefaultModel: String,
    @Value("\${embabel.models.llms.document-edit}") private val documentEditDefaultModel: String,
    /**
     * Per-role LLM timeouts, in seconds (2026-08-28, from a real run: "make
     * each of the stories longer, around 300 words each" against a local 8B
     * model). Embabel's own default is 60s, which is fine for a classifier
     * and hopeless for a step whose whole job is to WRITE several hundred
     * words - every attempt was cancelled mid-generation and retried, so the
     * document was never edited at all and Ollama spent ten minutes
     * producing text nobody would ever see.
     *
     * Only the two generating roles get one. The strategy classifier and the
     * tool-selection model answer in a sentence; if either ever takes a
     * minute, something is wrong and the timeout should fire.
     */
    @Value("\${springchat3.llm-timeouts.document-edit-seconds:900}")
    private val documentEditTimeoutSeconds: Long,
    @Value("\${springchat3.llm-timeouts.generation-seconds:300}")
    private val generationTimeoutSeconds: Long,
) {
    val objectMapper = ObjectMapper()
    private val log = LoggerFactory.getLogger(ChatAgent::class.java)

    /**
     * Text that means "this model meant to call a tool, and the platform
     * didn't notice" - see [leakedToolCallFailure]. Mistral-family models in
     * particular emit `[TOOL_CALLS]` inline when their Ollama template
     * doesn't map it onto the structured tool-call field.
     */
    private val TOOL_CALL_LEAK_MARKERS = listOf("[TOOL_CALLS]", "<tool_call>", "<|tool_call|>")

    /**
     * Verbs that could mean "change or create a document", for
     * [looksLikeDocumentChange]. Matched as substrings, so German stems are
     * given without their endings ("ergänz" covers ergänze/ergänzen/ergänzt)
     * and the English ones cover their own -e/-ed/-ing forms.
     */
    /**
     * [analyzeMessage]'s step label, named once because it is now also the
     * [ToolCallSummary.step] its tool calls carry (2026-08-28) - the two
     * have to stay identical for the UI to nest them under that row.
     */
    private val ANALYZE_STEP_NAME = "Analyzing message ..."

    /**
     * One fenced block spanning an entire reply: group 1 is the info string
     * ("markdown", "kotlin", ""), group 2 the body. Used by
     * [cleanAnswerText] - see its doc comment for why only some info
     * strings are unwrapped.
     */
    private val WHOLE_REPLY_FENCE = Regex("^```([A-Za-z0-9_+-]*)[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n?```$")

    /** Info strings whose fence [cleanAnswerText] removes rather than keeps. */
    private val MARKDOWN_FENCE_INFO = setOf("markdown", "md")

    /** Cap on [editOutcome]'s trace line - long enough for any edit tool's own sentence. */
    private val EDIT_OUTCOME_MAX_CHARS = 240

    private val WHITESPACE = Regex("\\s+")

    private val DOCUMENT_CHANGE_KEYWORDS = listOf(
        // create
        "create", "write", "draft", "new ", "generate", "erstell", "schreib", "verfass", "entwirf",
        // change
        "change", "edit", "update", "revise", "rewrite", "reword", "rephrase", "correct", "fix",
        "replace", "insert", "append", "add ", "remove", "delete", "rename", "translate", "format",
        "shorten", "expand", "polish", "save", "keep a copy",
        // Comparatives (2026-08-29). "shorten" was here but not "shorter",
        // and nothing covered "longer" at all - so "make the stories longer",
        // the exact phrasing of a real failed request, only ever reached this
        // step because that message ALSO happened to say "edit". A question
        // like "how much longer is chapter 2?" now costs one LLM call that
        // does nothing, which is the trade this whole list already makes.
        "longer", "shorter", "bigger", "smaller", "laenger", "läng", "groesser", "größer",
        // Formatting (2026-08-29, with the write half of the Word formatting
        // tools). "format"/"formatier" were already here, but they only cover
        // the word itself: "make the font bigger", "use Heading 1 for the
        // titles" and "Schriftart aendern" all named no verb this list knew,
        // so documentEdit short-circuited and no formatting tool could ever
        // be reached - silently, which is the worst shape for a miss.
        "font", "bold", "italic", "heading", "style", "indent", "spacing",
        "schrift", "fett", "kursiv", "ueberschrift", "überschrift", "formatvorlage", "einzug",
        // No umlaut-less fallbacks for "änder" or "kürz": "ander" matches
        // "andere" and "kurz" matches "kurz zusammenfassen", both of which
        // are ordinary question words here.
        "änder", "bearbeit", "überarbeit", "aktualisier", "ersetz", "einfüg", "füg", "ergänz",
        "lösch", "entfern", "umbenenn", "korrigier", "streich", "kürz", "übersetz", "formatier",
        "speicher",
    )

    /**
     * Resolves the LLM for tool selection - [ModelRoleKeys.TOOL_SELECTION]'s
     * override if one applies to this caller (2026-08-22, see
     * springchat3_settings.md in project memory), else Embabel's own
     * `default-llm` (`Ai.withDefaultLlm()`, unchanged from before this
     * feature). A separate helper from [llmForRole] because tool selection
     * has no real Embabel role name to fall back to - it's `default-llm`,
     * not a `embabel.models.llms.*` entry.
     *
     * Reads [ChatRequest.modelOverrides] rather than the settings store since
     * 2026-08-25: model choice is per user now, and this agent is a singleton
     * that never learns who is asking - see that field's own doc comment.
     */
    private fun toolSelectionLlm(request: ChatRequest, context: OperationContext) =
        request.modelOverrides[ModelRoleKeys.TOOL_SELECTION]
            ?.let { context.ai().withLlm(it) }
            ?: context.ai().withDefaultLlm()

    /**
     * Resolves the LLM for [role] (one of [ModelRoleKeys.GENERATION]/
     * [ModelRoleKeys.DOCUMENT_SEARCH_STRATEGY], both real Embabel role names
     * matching `embabel.models.llms.*`) - this caller's override for that
     * role if one applies, else `Ai.withLlmByRole(role)` exactly as before
     * this feature. Embabel itself has no supported way to change a role's
     * configured model at runtime (`embabel.models.*` is bound once at
     * startup, see [AppSettingsStore]'s own doc comment) - `Ai.withLlm(exact
     * model name)` sidesteps that entirely by naming the model directly
     * rather than going through the role indirection, which works because
     * embabel-agent-ollama-autoconfigure already registers every locally
     * pulled Ollama model as its own selectable LLM by tag.
     */
    private fun llmForRole(request: ChatRequest, context: OperationContext, role: String) =
        context.ai().withLlm(withTimeout(llmOptionsForRole(request, role), role))

    /**
     * The same resolution [llmForRole] always did, expressed as [LlmOptions]
     * rather than as the two `Ai` shortcuts (2026-08-28), because a timeout
     * can only be attached to the options: `withLlm(name)` and
     * `withLlmByRole(role)` build exactly these two values internally.
     */
    private fun llmOptionsForRole(request: ChatRequest, role: String): LlmOptions =
        request.modelOverrides[role]
            ?.let { LlmOptions.withModel(it) }
            ?: LlmOptions.withLlmForRole(role)

    /**
     * Attaches this role's timeout, when it has one - see the constructor
     * properties for why only the generating roles do.
     *
     * A timeout here is not a nicety: Embabel cancels the call and then
     * RETRIES it, and a retry re-runs the whole tool loop. For [documentEdit]
     * that means the model can re-issue an edit it already applied, which is
     * why [ch.arcticsoft.springchat3.tools.WordDocumentEditTool] refuses a
     * repeated identical write - the two guards belong together.
     */
    private fun withTimeout(options: LlmOptions, role: String): LlmOptions = when (role) {
        ModelRoleKeys.DOCUMENT_EDIT -> options.withTimeout(Duration.ofSeconds(documentEditTimeoutSeconds))
        ModelRoleKeys.GENERATION -> options.withTimeout(Duration.ofSeconds(generationTimeoutSeconds))
        else -> options
    }

    /**
     * The exact model tag actually backing [role]'s calls right now - the
     * same resolution [llmForRole] applies to pick which LLM to call
     * (override from the settings popup if one is set, else [default]),
     * just returning the model *name* rather than a ready-to-use `PromptRunner`.
     * Used to show which model actually ran a given step in the UI trace
     * (2026-08-22, user's own request - see springchat3_settings.md in
     * project memory) so switching a role's model in the settings popup is
     * visibly reflected in the trace on the very next turn, not just
     * inferred from `application.yml`.
     */
    private fun resolvedModel(request: ChatRequest, role: String, default: String): String =
        request.modelOverrides[role] ?: default

    /**
     * [documentSearchStrategy]'s trace/progress label, including the exact
     * model actually deciding this turn's strategy - recomputed (rather than
     * a stored constant, now that it's no longer a fixed string) in both
     * [documentSearchStrategy] itself and [answer]'s `steps` list, which is
     * safe because both read the same [ChatRequest], fixed when the turn was
     * authorized (since 2026-08-25; it was the settings store's own
     * immutability during a request before that). A single function keeps
     * both call sites from silently drifting apart the way a plain constant
     * used to.
     */
    private fun documentSearchStrategyStepName(request: ChatRequest): String =
        "Document search strategy (${resolvedModel(request, ModelRoleKeys.DOCUMENT_SEARCH_STRATEGY, documentSearchStrategyDefaultModel)}) ..."

    /** Same idea as [documentSearchStrategyStepName], for [documentEdit]'s own step. */
    private fun documentEditStepName(request: ChatRequest): String =
        "Editing document (${resolvedModel(request, ModelRoleKeys.DOCUMENT_EDIT, documentEditDefaultModel)}) ..."

    /** Same idea as [documentSearchStrategyStepName], for [answer]'s own generation step. */
    private fun generatingAnswerStepName(request: ChatRequest): String =
        "Generating answer (${resolvedModel(request, ModelRoleKeys.GENERATION, generationDefaultModel)}) ..."

    /**
     * Short-circuits to no LLM call at all when tool use is switched off in
     * the settings popup by *this caller* (2026-08-22, per user since
     * 2026-08-25 - see springchat3_settings.md in project memory) - with no tools to hand the model, there is
     * nothing left for this step to decide, so skip the round-trip entirely
     * rather than calling with an empty tool list. Emits no progress events
     * either, so "Analyzing message ..." simply doesn't appear in the trace
     * for that turn - same "don't show a step that did nothing" convention
     * [documentSearchStrategy] already uses for its own no-op short-circuits.
     */
    @Action
    fun analyzeMessage(request: ChatRequest, context: OperationContext): ToolResults {
        if (!request.toolsEnabled) {
            return ToolResults(emptyList())
        }

        log.debug("analyze message: {}", request.message)
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted(ANALYZE_STEP_NAME))
        val start = System.currentTimeMillis()

        val currentLocationTool = CurrentLocationTool(geoTool, request.latitude, request.longitude)
        // Word document READ tools (2026-08-23, user's own decision
        // "analyzeMessage can use tools to read the document. editing a
        // document should be made by answer.") - per-request like
        // CurrentLocationTool, since they're scoped to whichever project is
        // active for this turn. The editing counterpart is deliberately NOT
        // here; see WordDocumentEditTool's own doc comment and [answer].
        //
        // Scoped to the documents the user selected, same as documentEdit
        // (2026-08-23, user's own report: with "First Document.docx"
        // selected, the reply "contained aspects from document 'PID E2E
        // Challenges and Opportunities'"). Reading is what puts a document's
        // text into the answer, so leaving this project-wide would have kept
        // exactly the behaviour that was complained about, one step earlier
        // in the pipeline. It also makes these tools agree with the rest of
        // the turn: vector retrieval below already looks only at
        // request.documentIds, and skips entirely when nothing is attached.
        val wordDocumentReadTool = WordDocumentReadTool(wordDocumentWorkspace, request.spaceId, request.documentIds.toSet())
        // Typed List<GatheringTool>, not List<ChatTool> (2026-08-23) - the
        // compiler is what keeps an EditingTool out of this step now, rather
        // than a convention about which classes get @Component. See
        // ChatToolRegistry / the EditingTool interface.
        val toolObjects: List<GatheringTool> =
            chatToolRegistry.gatheringTools() + currentLocationTool + wordDocumentReadTool
        log.debug(
            "toolObjects provided to context.ai(): {}",
            toolObjects.joinToString { it::class.simpleName ?: it.toString() },
        )
        val toolCallbacks = MethodToolCallbackProvider.builder()
            .toolObjects(*toolObjects.toTypedArray())
            .build()
            .toolCallbacks
        toolCallbacks.forEach { callback ->
            val definition = callback.toolDefinition
            log.trace(
                "Tool definition for LLM tool selection - name: {}, description: {}, inputSchema: {}",
                definition.name(),
                definition.description(),
                definition.inputSchema(),
            )
        }

        // Fix for a real 2026-08-22 failure: "Can you summarize Agentic AI
        // Program?" (a question about an attached document) triggered
        // lookup_place + get_meteoswiss_weather - nonsensical tool calls
        // with nothing to do with the message. Root cause: analyzeMessage
        // never told the small model a document was even attached, so a
        // message it didn't otherwise recognize looked like "guess
        // something" rather than "this needs no tool". documentNote gives
        // it just enough to recognize that case (filename only - the
        // document's actual content stays out of this model's context
        // budget entirely, same reasoning [answer] uses DocumentIndex's
        // retrieval for instead of routing a document through this small
        // model - see springchat3_document_qa.md in project memory).
        // Multi-document (2026-08-22, see ChatRequest.documentIds's doc
        // comment) - lists every attached document by name rather than
        // just one, so this small model recognizes a question about any of
        // them, not only a single attached document.
        val attachedDocsForNote = request.documentIds.mapNotNull { documentStore.get(it) }
        val documentNote = when {
            attachedDocsForNote.isEmpty() -> "No document is attached to this conversation."
            attachedDocsForNote.size == 1 ->
                "An attached document, \"${attachedDocsForNote[0].filename}\", is available for this " +
                    "conversation - questions about it (e.g. \"summarize it\", \"what does " +
                    "it say about X\") are answered from its content by a later step, not by " +
                    "any tool here. If the message is about the attached document, no tool " +
                    "call is needed for that."
            else -> {
                val names = attachedDocsForNote.joinToString(", ") { "\"${it.filename}\"" }
                "Several attached documents ($names) are available for this conversation - " +
                    "questions about them (e.g. \"summarize them\", \"what do they say about " +
                    "X\") are answered from their content by a later step, not by any tool " +
                    "here. If the message is about the attached documents, no tool call is " +
                    "needed for that."
            }
        }

        val analyzeMessagePrompt = """
            The user's message was: "${request.message}"

            $documentNote

            Call whichever of your available tools, if any, would
            help answer it - you may call more than one, or none at
            all for a message like "hello", something you already
            know without any tool's help, or a question about an
            attached document (see above).

            Only call a tool when the message clearly needs what
            that tool actually does - lookup_place/
            get_meteoswiss_weather for a place or its weather,
            get_user_location for the user's own location. If the
            message doesn't clearly call for one of those, call no
            tools at all, even if you don't otherwise recognize what
            is being asked. Guessing a tool "just in case" is wrong
            just as often as skipping one that was genuinely needed
            - for example, "Can you summarize the attached document?"
            needs no tool call at all, not a place or weather lookup.

            Once you are done calling tools (or decided none were
            needed), your FINAL response must be a single JSON object
            with exactly one field named "note", holding a short
            one-line summary of what you found. The actual reply to
            the user comes from a separate step working from your
            tools' raw results directly, not from this note, so keep
            it brief - but it must always be wrapped in that exact
            JSON shape, never returned as plain text on its own.

            Correct:   {"note": "Wädenswil"}
            Incorrect: Wädenswil

            Correct:   {"note": "nothing needed"}
            Incorrect: nothing needed

            Correct:   {"note": "checked the weather for Bern"}
            Incorrect: checked the weather for Bern

            This applies no matter how short or obvious the note is -
            even a single word, a place name, or "nothing needed"
            must still be wrapped as {"note": "..."}. Never respond
            with a bare word or unquoted text by itself.

            Respond with raw JSON only. Do not wrap it in markdown
            code fences or backticks, and do not add any other text
            before or after the JSON object.
            """.trimIndent()
        log.trace("analyzeMessage prompt (tool identification):\n{}", analyzeMessagePrompt)
        val processId = context.agentProcess.id
        log.trace("analyzeMessage processId (context.agentProcess.id): {}", processId)
        val (note, executions) = toolCallBridge.withCapture(processId, request.correlationId) {
            toolSelectionLlm(request, context)
                .withToolObjects(toolObjects)
                .createObject(
                    analyzeMessagePrompt,
                    ToolGatheringNote::class.java,
                )
        }
        log.debug("Tool selection response (analyzeMessage's ToolGatheringNote): {}", note?.note ?: "<unavailable>")

        executions.forEach {
            val json = objectMapper
                .readTree(it.rawOutput)
                .toPrettyString()
            log.trace("Executions {}: \n{}", it.tool, json)
        }

        val seconds = (System.currentTimeMillis() - start) / 1000.0
        progressBus.emit(request.correlationId, ChatProgressEvent.StepFinished(ANALYZE_STEP_NAME, seconds))

        return ToolResults(executions, timings = listOf(StepTiming(ANALYZE_STEP_NAME, seconds)))
    }

    /**
     * Best-effort heuristic shared by [ToolCallProgressBridge] (for the live
     * [ChatProgressEvent.ToolFinished] event) and the final
     * [ToolCallSummary.failed] built below - see [ToolCallSummary]'s doc
     * comment in ChatModel.kt for why this is a heuristic rather than a hard
     * guarantee.
     */
    private fun isToolError(output: String): Boolean = output.trimStart().startsWith("{\"error\"")

    private fun toolCallSummary(execution: ToolExecution, step: String? = null, outcome: String? = null) =
        ToolCallSummary(
            tool = execution.tool,
            input = execution.input,
            failed = isToolError(execution.rawOutput),
            seconds = execution.durationMs / 1000.0,
            step = step,
            outcome = outcome,
        )

    /**
     * One line of what a [documentEdit] tool call actually did, for the
     * trace row under "Editing document ..." (2026-08-28).
     *
     * The tools already answer in a sentence meant to be read ("Appended 2
     * paragraphs to \"Offer.docx\". It now has 31 paragraphs ...") or as
     * `{"error": ...}`, so this only unwraps the error envelope, flattens
     * whitespace and caps the length - a read tool called in the same step
     * can return a whole document, and the trace is not the place for it.
     * The full text is still in the reply itself, which answer() writes from
     * these same results.
     */
    private fun editOutcome(rawOutput: String): String {
        val trimmed = rawOutput.trim()
        val unwrapped = if (trimmed.startsWith("{")) {
            try {
                objectMapper.readTree(trimmed).get("error")?.asText() ?: trimmed
            } catch (e: Exception) {
                trimmed
            }
        } else {
            trimmed
        }
        val oneLine = unwrapped.replace(WHITESPACE, " ").trim()
        return if (oneLine.length <= EDIT_OUTCOME_MAX_CHARS) {
            oneLine
        } else {
            oneLine.take(EDIT_OUTCOME_MAX_CHARS).trimEnd() + "..."
        }
    }

    /**
     * Renders a document's extracted outline as an indented plain-text list
     * (e.g. "- Module 1 – Foo (page 3)"), fed directly into the generation
     * prompt in place of vector-search passages when [answer] takes the
     * structure-search path - also used to show [documentSearchStrategy]'s
     * own classification model the outline it's judging a question against.
     * No markdown/heading syntax here - the prompt's own formattingGuidance
     * decides how the *reply* is formatted; this is just raw source
     * material, same role [relevantChunks] plays in the vector-search path.
     */
    private fun flattenStructure(nodes: List<StructureNode>, depth: Int = 0): String =
        nodes.joinToString("\n") { node ->
            val indent = "  ".repeat(depth)
            val page = node.pageNumber?.let { " (page $it)" }.orEmpty()
            val line = "$indent- ${node.title}$page"
            if (node.children.isEmpty()) line else line + "\n" + flattenStructure(node.children, depth + 1)
        }

    /**
     * New pipeline step (2026-08-22, see springchat3_document_qa.md in
     * project memory) deciding, via [ModelRoleKeys.DOCUMENT_SEARCH_STRATEGY]'s
     * own small dedicated model, whether [answer] should answer a document
     * question from the document's own extracted outline
     * ([DocumentStructureStore]), by searching its content
     * ([DocumentIndex.search]), or - v4 (same day) - both at once, e.g. a
     * question that names a section by its outline title and then asks
     * about its content ("summarize chapter 3") needs the outline to
     * identify which part of the document is meant AND a content search to
     * actually answer what it says. Replaces an earlier plain keyword
     * heuristic (`looksStructural`, now removed) that over-triggered on
     * ordinary phrasing ("what are the...", "overview", "list") having
     * nothing to do with the document's actual structure.
     *
     * Short-circuits to no LLM call at all when no document is attached
     * (the common case, most turns) - not surfaced as a step in that case
     * either, same as [answer]'s own retrieval step, which also only shows
     * up for a document-attached turn. When a document *is* attached but
     * has no extracted structure to classify against, it still shows as a
     * step (near-instant, no LLM call) - that's honest information too:
     * the decision took ~0s because there was nothing to decide. Also falls
     * back the same way (plain vector search, no structure) if the
     * classification call itself throws - this is a small, dedicated model
     * making one narrow judgment call per turn, not something this app
     * should let take down an otherwise-answerable document question.
     *
     * **Multi-document (2026-08-22, see [ChatRequest.documentIds]'s doc
     * comment):** when more than one document is attached, this still makes
     * one classification call per turn, not one per document - every
     * attached document's outline (for whichever of them actually have one)
     * is shown together, labeled by filename, and the single resulting
     * decision is applied to each document independently in [answer] (see
     * [DocumentSearchStrategy]'s own doc comment for why).
     *
     * Surfaced to the UI as its own timed step (2026-08-22, user's own
     * request, see springchat3_document_qa.md in project memory) - was
     * previously folded silently into the retrieval step's own reported
     * time; now measured and reported on its own, the same way
     * [analyzeMessage] is, so [answer]'s retrieval-block timer no longer
     * needs to account for it at all. Note: this action and [analyzeMessage]
     * both depend only on [ChatRequest]/[OperationContext], so Embabel may
     * run them concurrently or in either order - the *live* streaming trace
     * shows steps in actual event-arrival order, so "Document search
     * strategy ..." could occasionally appear to arrive before "Analyzing
     * message ..." there even though the *finished* trace always lists them
     * in the fixed order [answer] builds `steps` in. Not addressed here;
     * watch for it if it turns out to look wrong in practice.
     */
    @Action
    fun documentSearchStrategy(request: ChatRequest, context: OperationContext): DocumentSearchStrategy {
        log.debug("documentSearchStrategy ... {}", request.message)
        if (request.documentIds.isEmpty()) {
            return DocumentSearchStrategy(useStructure = false, useVector = false)
        }

        val stepName = documentSearchStrategyStepName(request)
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted(stepName))
        val start = System.currentTimeMillis()

        // Multi-document (2026-08-22, see DocumentSearchStrategy's doc
        // comment in ChatModel.kt) - classify once against every attached
        // document's outline that actually has one (labeled by filename),
        // rather than one LLM call per document; a document with no outline
        // at all simply contributes nothing here and falls back to vector
        // search in answer() regardless of this classification.
        val outlinesByDoc = request.documentIds.mapNotNull { id ->
            documentStructureStore.get(id)?.let { structure -> id to structure }
        }
        val strategy = if (outlinesByDoc.isEmpty()) {
            DocumentSearchStrategy(useStructure = false, useVector = true)
        } else {
            val outlinesText = outlinesByDoc.joinToString("\n\n") { (id, structure) ->
                val filename = documentStore.get(id)?.filename ?: id
                "Document \"$filename\":\n${flattenStructure(structure.nodes)}"
            }
            val multiple = outlinesByDoc.size > 1
            val prompt = """
                The user's question was: "${request.message}"

                ${if (multiple) "The attached documents have" else "This document has"} the following table${if (multiple) "s" else ""} of contents/outline:

                $outlinesText

                This decision applies to every attached document, not just
                the one(s) shown above - a document with no outline of its
                own is always searched by content regardless of what you
                decide here.

                Decide, independently, whether answering this question needs
                each of these two sources:

                1. The outline(s) above (structure) - useful when the
                   question is about a document's own organization: what
                   sections, chapters, or modules it has, how many there
                   are, their titles, or where in the document something is
                   located.
                2. A search of the documents' actual written content
                   (vector) - needed whenever answering requires what a
                   document actually SAYS rather than how it's organized:
                   facts, arguments, numbers, definitions, or anything else
                   found in the body text, not the outline.

                Always set "useVector" to true for any request to summarize
                a document (or a part of it), to explain something in it or
                help understand it, or that asks for details or specifics
                about a topic - these always need a semantic search of the
                actual content, never the outline alone, even if the
                outline's own titles look like they already cover it.

                Most questions need only one of these two sources. Some need
                both - e.g. a question that names a specific chapter or
                section by its outline title and then asks about its content
                ("summarize chapter 3", "what does the pricing section say?")
                needs the outline to identify which part of the document is
                meant, AND the content search to actually answer what it
                says. If you are ever unsure whether the content search is
                needed, set "useVector" to true - it is the safer default.

                Respond with raw JSON only: one object with two boolean
                fields, "useStructure" and "useVector". Do not wrap it in
                markdown code fences or backticks, and do not add any other
                text before or after the JSON object.

                Correct:   {"useStructure": true, "useVector": false}
                Correct:   {"useStructure": false, "useVector": true}
                Correct:   {"useStructure": true, "useVector": true}
                Incorrect: {"preferOutline": true}
                """.trimIndent()
            log.trace("documentSearchStrategy prompt:\n{}", prompt)

            try {
                val classification = llmForRole(request, context, ModelRoleKeys.DOCUMENT_SEARCH_STRATEGY)
                    .createObject(prompt, DocumentQuestionClassification::class.java)
                log.debug(
                    "documentSearchStrategy classification: useStructure={} useVector={}",
                    classification.useStructure,
                    classification.useVector,
                )
                DocumentSearchStrategy(
                    useStructure = classification.useStructure,
                    useVector = classification.useVector,
                )
            } catch (e: Exception) {
                log.warn("documentSearchStrategy classification failed - defaulting to vector search", e)
                DocumentSearchStrategy(useStructure = false, useVector = true)
            }
        }

        val seconds = (System.currentTimeMillis() - start) / 1000.0
        progressBus.emit(request.correlationId, ChatProgressEvent.StepFinished(stepName, seconds))
        return strategy.copy(seconds = seconds)
    }

    /**
     * The one step allowed to CHANGE anything (2026-08-23). Sits between
     * [documentSearchStrategy] and [answer]: decides whether the user asked
     * for a document to be created or changed, and if so does it, via the
     * Word tools ([WordDocumentEditTool], plus the read tools so it can
     * check paragraph numbers immediately before using them).
     *
     * **Why its own action rather than tools hung off [answer]**, which is
     * where this first landed and where the user's own earlier decision put
     * it ("editing a document should be made by answer"), before they asked
     * to reconsider the pipeline as a whole:
     *
     *  1. [answer] is `@AchievesGoal`, and when this was written it also had
     *     to emit a parseable object. Structured output plus a tool-call loop
     *     in one call is the shakiest combination available, and the recovery
     *     for malformed JSON is to re-prompt - which can replay the tool
     *     calls. "Append this paragraph" applied twice is a silently corrupted
     *     document. A step with side effects must not be the one whose output
     *     format can force a redo. ([answer] itself stopped asking for an
     *     object on 2026-08-25, for the same family of reasons.)
     *  2. It separates deciding to change a document from describing the
     *     change. The "only when the user explicitly asked" guardrail is now
     *     this step's entire prompt, instead of one paragraph buried in a
     *     prompt otherwise about Markdown formatting rules.
     *  3. It gets what every other step here has: its own model role
     *     ([ModelRoleKeys.DOCUMENT_EDIT], selectable in the settings popup),
     *     its own row in the UI timeline, and its own short-circuit.
     *
     * Embabel orders this before [answer] the same way [documentSearchStrategy]
     * is ordered - by data dependency, not by declaration order: [answer]
     * takes a [DocumentEdits] parameter, so this action has to run first to
     * produce one.
     *
     * Short-circuits to no LLM call at all - the common case, most turns -
     * when editing is switched off, when no project is active, when the
     * active project has no Word documents to edit and the message is
     * clearly not asking for one to be created, or when the message asks
     * for no change at all ([looksLikeDocumentChange]). The last two are
     * cheap keyword pre-filters rather than classifier calls: getting one
     * wrong costs one skipped feature invocation, and the alternative (an
     * LLM call on every single turn just to rule editing out) costs more
     * than the feature is worth.
     *
     * The verb pre-filter earns more than the call it saves (2026-08-28):
     * without it every turn with a Word document selected - which is every
     * document Q&A turn, this app's main use - paid a full document-editing
     * LLM call with the write tools armed, only to decide "no". That call
     * was also the one window in which an unasked-for edit could happen.
     */
    @Action
    fun documentEdit(request: ChatRequest, context: OperationContext): DocumentEdits {
        // Every short-circuit below logs its reason. Without that this step
        // is indistinguishable from never having been planned at all: it
        // emits no progress event and makes no LLM call when it skips, so
        // "documentEdit is not called" and "documentEdit ran and declined"
        // look identical from outside (2026-08-23 - which is exactly the
        // confusion the first version caused, with document editing simply
        // switched off in settings).
        // Still read from the store rather than the request, unlike tool use
        // and the model choices next to it in the same popup (2026-08-25):
        // this one is server policy, not a preference. A permission a user
        // grants themselves is not a permission, and these edits land in
        // documents other people share - so it stays admin-only and global.
        if (!appSettingsStore.get().documentEditingEnabled) {
            log.debug("documentEdit skipped: document editing is switched off in settings")
            /*
             * Silence is wrong when the user clearly expected an edit
             * (2026-08-25, from a real report: a document was unlocked, an
             * edit was asked for, and the only trace of the refusal was this
             * DEBUG line in the server log - the reply just answered as
             * though nothing had been requested).
             *
             * Intent is read from the unlocked set rather than from keywords
             * in the message: unlocking a document is a deliberate act aimed
             * at exactly this, so "they unlocked one of the documents they
             * attached" is a far better signal than guessing at verbs, and it
             * stays silent for everyone who never unlocked anything - this
             * step runs on every turn with a Word document selected, so a
             * blanket notice here would be noise on most of them.
             */
            val unlockedAndAttached = request.documentIds.toSet().intersect(request.editableDocumentIds)
            if (unlockedAndAttached.isNotEmpty()) {
                return DocumentEdits(
                    listOf(
                        ToolExecution(
                            tool = "document_edit",
                            input = "",
                            rawOutput = """{"error": "Document editing is switched off for this server, so nothing """ +
                                """was changed. The document's own padlock is unlocked, but an administrator also """ +
                                """has to enable \"Document editing\" under Server policy in Settings."}""",
                            durationMs = 0,
                        ),
                    ),
                )
            }
            return DocumentEdits()
        }
        // Set server-side from the caller's role in this space (2026-08-24,
        // shared spaces - see ChatRequest.documentEditingAllowed): a viewer
        // gets an agent that reads and answers but never writes. Checked
        // alongside the global setting rather than folded into it - one is
        // "this app does not edit documents", the other "this person does
        // not", and a log line that can't tell them apart is exactly the
        // confusion the surrounding comment warns about.
        if (!request.documentEditingAllowed) {
            log.debug("documentEdit skipped: this user has view-only access to space {}", request.spaceId)
            return DocumentEdits()
        }
        if (request.spaceId == null) {
            log.debug("documentEdit skipped: no active space, so there is nothing this step could reach")
            return DocumentEdits()
        }
        // Only the documents the user has attached to this turn are in scope
        // (2026-08-23, user's own report: "I had 0 documents selected" and
        // this step still logged "running against 2 Word document(s)").
        // Selection is the user pointing at something; without it there is no
        // request to change any particular document, only two documents that
        // happen to exist. The same set is handed to both tool objects below,
        // so this is a scope, not a hint - see WordDocumentEditTool.
        val selectedIds = request.documentIds.toSet()
        val documents = wordDocumentWorkspace.list(request.spaceId, selectedIds)
        if (documents.isEmpty() && !looksLikeDocumentCreation(request.message)) {
            log.debug(
                "documentEdit skipped: none of the {} Word document(s) in space {} are selected, and \"{}\" " +
                    "doesn't look like a request to create one",
                wordDocumentWorkspace.list(request.spaceId).size,
                request.spaceId,
                request.message,
            )
            return DocumentEdits()
        }
        // Nothing in the message asks for anything to change, so there is
        // no decision left for the model to make (2026-08-28). Deliberately
        // asymmetric, the same trade looksLikeDocumentCreation already
        // makes: a false negative costs a skipped feature invocation and
        // the user rephrases, a false positive costs one LLM call that then
        // does nothing.
        // The verb pre-filter is skipped entirely when the previous turn left
        // an editing question open (2026-08-29). A reply to a question this
        // app asked - "yes", "the first one", "500 each" - contains no change
        // verb by nature, so the filter that exists to keep this step off
        // ordinary Q&A turns was also the thing swallowing every answer to
        // its own questions. See [ChatRequest.pendingEdit]; it comes from the
        // immediately previous assistant entry of this same session and
        // space, so it can never be stale by more than one turn.
        if (!looksLikeDocumentChange(request.message) && request.pendingEdit == null) {
            log.debug(
                "documentEdit skipped: \"{}\" asks for no change to a document",
                request.message,
            )
            return DocumentEdits()
        }
        if (request.pendingEdit != null) {
            log.debug(
                "documentEdit running on a follow-up - the previous turn asked: {}",
                request.pendingEdit.question,
            )
        }
        log.debug(
            "documentEdit running against {} selected Word document(s) in space {}",
            documents.size,
            request.spaceId,
        )

        val stepName = documentEditStepName(request)
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted(stepName))
        val start = System.currentTimeMillis()

        // Every document listed here is one the user selected - the list is
        // already filtered above - so there is no "<- selected" annotation to
        // make any more.
        //
        // The prompt deliberately says NOTHING about which of several
        // selected documents to pick (2026-08-28). It used to: it told the
        // model to change nothing and name the candidates so the user could
        // choose. But this step's note text is logged and dropped - only
        // ToolExecutions cross into answer() - so that question reached
        // nobody, and the model holding back read to the user as though no
        // change had been asked for. Ambiguity is left entirely to
        // WordDocumentEditTool.targetedByUser, which refuses in code and
        // returns an error naming every candidate: the only version of that
        // question the user can actually see.
        // Scope paragraph, split in two on 2026-08-28 after a real refusal:
        // asked to "save the summary in a new document" with nothing
        // selected, the model answered "I cannot create a document" and
        // called no tool. The old single wording listed the selected
        // documents as "the only ones you may read or change" and rendered
        // "(none - the user has selected no document)" when there were
        // none - which reads as a blanket "you may touch nothing", one line
        // above being asked to decide whether to CREATE. Creating needs no
        // selection, and now the prompt says so in both branches.
        val scope = if (documents.isEmpty()) {
            """
            The user has selected no document in the side panel, so there is
            nothing here for you to read or change. Creating a NEW document
            needs no selection - the create tool is available to you, so if
            that is what was asked for, do it.
            """.trimIndent()
        } else {
            """
            The Word documents the user has selected in the side panel are
            the only ones you may read or change:
            ${documents.joinToString("\n            ") { doc -> "- \"${doc.filename}\"" }}

            That list limits reading and changing only. You may also create a
            NEW document if that is what was asked for - creating needs no
            selection.
            """.trimIndent()
        }
        // Assembled as separate paragraphs the way answer() builds its own,
        // rather than one raw string with $scope interpolated into it. Same
        // reason the filename list above is joined with its own indent:
        // trimIndent() trims to the SHALLOWEST line, so one interpolated
        // line sitting at column 0 disables it for the whole block and the
        // rest keeps its source indentation. (The old single-string prompt
        // had exactly that bug whenever two or more documents were listed.)
        // Mentioned only when there IS a previous reply AND this message
        // actually asks for it to be saved (2026-08-29). The first half is
        // the original 2026-08-28 rule: naming a tool that can only fail
        // invites the call and wastes the turn.
        //
        // The second half is what was missing, and it cost a real edit. A
        // previous reply exists on every turn after the first, so the hint
        // was on offer permanently - including for "edit greeks.docx. make
        // the stories longer", where the model took the tool that needs only
        // a filename over the edit path that needs a read and paragraph
        // numbers, and saved the prior turn's apology as "Summary.docx"
        // while the document it was asked to change went untouched. See
        // SaveAnswerIntent for why that filter is strict where
        // looksLikeDocumentChange is generous.
        //
        // The prompt is still only the soft layer: the same check runs
        // inside WordDocumentEditTool, which is what actually refuses, with
        // an error the user gets told about.
        val saveAnswerHint = if (
            !request.previousAnswer.isNullOrBlank() && SaveAnswerIntent.isAskedFor(request.message)
        ) {
            """
            Your previous reply in this chat can be saved as a new document
            exactly as written, with save_answer_as_word_document - it takes
            only a filename. Use it when the user asks to save, keep or write
            down "the answer", "the summary" or "what you just said". Do not
            retype that text into create_word_document: you would be saving
            your own paraphrase of it instead of the reply they read.
            """.trimIndent()
        } else {
            null
        }
        // Assembled as its own paragraphs rather than interpolated into one
        // trimIndent block: the question and the earlier message are both
        // values that can span lines, and trimIndent runs AFTER interpolation
        // - one line landing at column 0 disables the trim for everything.
        val pendingEditContext = request.pendingEdit?.let { pending ->
            listOf(
                "This is a FOLLOW-UP. On the previous turn you changed nothing and said:",
                pending.question,
                "The request you were answering then was:",
                pending.askedAbout,
                """
                The user's message above is their reply to that. Read it as the
                answer to your own question: "yes" means go ahead with exactly
                what you proposed, and a short phrase like "the first one" or
                "500 each" supplies the detail you asked for. You have already
                asked once - do not ask the same thing again unless their reply
                genuinely still leaves you unable to act. Make the change now.
                """.trimIndent(),
            ).joinToString("\n\n")
        }

        val prompt = listOfNotNull(
            pendingEditContext,
            // A plain string, not a raw one: a raw string cannot end with a
            // quote character without the ${'"'} dance.
            "The user's message was: \"${request.message}\"",
            scope,
            saveAnswerHint,
            """
            Decide whether this message is asking you to CREATE or CHANGE a
            document, and act accordingly:

            - If it is not - a question, a request for information, anything
              about what a document says rather than what it should say - do
              nothing at all. Call no tools. This is the normal case.
            - If it is, carry out exactly the change that was asked for,
              using the tools available to you. Read the document first if
              you need paragraph numbers: they shift with every edit, so
              read them immediately before you use them, never from memory.
              Change nothing beyond what was asked - do not tidy, reformat
              or improve anything on your own initiative.

            When a change covers several paragraphs, make it ONE TOOL CALL
            PER PARAGRAPH rather than one call carrying the whole rewritten
            document. Each call then finishes quickly and its result is
            already saved, so a slow or interrupted turn leaves the work so
            far in place instead of losing all of it. Read the document
            again between calls if you need the numbers.

            Never repeat a tool call you have already made. If a call
            reported that it changed something, that change is saved - call
            it again and the same edit is applied twice.
            """.trimIndent(),
            """
            What you can change about formatting: which STYLE a paragraph
            uses; a style's or the document's font, size, bold, italic and
            colour; the same directly on a range of paragraphs; the space
            above, below and between lines; alignment; and indentation.

            What you CANNOT change, at all: tables, page size, margins,
            orientation, headers and footers, borders, shading, highlighting,
            columns, images and page breaks. If the user asks for one of
            those, say plainly that it is not something you can do - do not
            substitute the nearest thing you CAN change and describe it as if
            it were what they asked for. Changing the font size when someone
            asked about line spacing is worse than saying no.
            """.trimIndent(),
            """
            You are not talking to the user here, and nothing you write in
            this reply reaches a document. Only a tool call changes anything.

            So: describing a tool is not calling one. Never write "I would
            use", "you could use", "first you would call", or a list of
            steps someone should follow - the person reading this cannot
            call these tools, and a plan is the same as having done nothing.
            If a change is wanted, make the call now.

            You DO have this document. The tools act on the real file, so
            never say you cannot see it, do not have it, or can only explain
            how it would be done.

            Never assume what the document contains. If you need to know its
            headings, its styles, its paragraph numbers or its formatting,
            CALL the read tool that answers that and use what it returns -
            guessing from the filename, from the conversation, or from an
            excerpt someone quoted is how the wrong paragraph gets changed.
            """.trimIndent(),
            """
            Then reply with a one-sentence note stating what you changed, or
            that no change was needed.
            """.trimIndent(),
        ).joinToString("\n\n")

        // Read tools alongside the editing ones: whoever edits has to be able
        // to check paragraph numbers in the same breath, since they are
        // positional and go stale after every write (see WordParagraph).
        val toolObjects: List<ChatTool> = chatToolRegistry.editingTools() +
            WordDocumentEditTool(
                wordDocumentWorkspace,
                wordDocumentService,
                request.spaceId,
                request.message,
                selectedIds,
                request.editableDocumentIds,
                request.previousAnswer,
            ) +
            // Scoped to the same selection as the editing tool: whoever is
            // about to change a document should not be able to read one it
            // isn't allowed to change. analyzeMessage's own read tool stays
            // space-wide - answering a question is a different job.
            WordDocumentReadTool(wordDocumentWorkspace, request.spaceId, selectedIds)

        // generateText, NOT createObject (2026-08-23, after a real failure -
        // see below). This step's own text output is only ever logged: what
        // matters is the tool calls it made, which the bridge captures
        // regardless. Asking for a structured object on top forced the model
        // to both tool-call AND emit parseable JSON in one response, and the
        // first real run failed exactly there ("Cannot deserialize value of
        // type ToolGatheringNote from Array value") - a pointless second way
        // for this step to fail.
        val (note, executions) = toolCallBridge.withCapture(context.agentProcess.id, request.correlationId) {
            llmForRole(request, context, ModelRoleKeys.DOCUMENT_EDIT)
                .withToolObjects(toolObjects)
                .generateText(prompt)
        }
        log.debug("documentEdit note: {} ({} tool call(s))", note ?: "<unavailable>", executions.size)

        val seconds = (System.currentTimeMillis() - start) / 1000.0
        progressBus.emit(request.correlationId, ChatProgressEvent.StepFinished(stepName, seconds))
        // The note crosses into [answer] since 2026-08-29 (see [DocumentEdits.note]
        // for what it is and is not). Cleaned on the way through for the same
        // reasons a reply is: this model can be a reasoning one, and a raw
        // scratchpad in the answer prompt is noise at best.
        //
        // Suppressed entirely when the leak detector fired. In that case the
        // note IS the failure - literal "[TOOL_CALLS]..." text - and
        // [leakedToolCallFailure] has already turned it into a proper error
        // result that says what went wrong in words the user can act on.
        // Passing the raw marker text along beside it would add nothing and
        // invite the answer model to try to make sense of it.
        val leaked = leakedToolCallFailure(request, note, executions)
        return DocumentEdits(
            executions = executions + leaked,
            seconds = seconds,
            note = if (leaked.isEmpty()) cleanAnswerText(note).takeIf { it.isNotBlank() } else null,
        )
    }

    /**
     * Detects a model that *tried* to call a tool but whose output was never
     * parsed as one, and turns it into a real failure the user gets told
     * about (2026-08-23, from a real run: the model returned the literal
     * text `[TOOL_CALLS]list_word_documents{}` and Embabel saw zero tool
     * calls).
     *
     * This is a model/Ollama-template problem, not an app one - some models
     * emit their tool-call syntax as ordinary content instead of the
     * structured `tool_calls` field the platform reads, and there is nothing
     * this code can do to make the call happen. What it CAN do is refuse to
     * fail silently: without this, the user asks for a change, nothing is
     * changed, and [answer] cheerfully replies as if no change had been
     * wanted. Returning a synthetic `{"error": ...}` execution instead means
     * [answer]'s existing tool-error guidance tells them plainly that the
     * change didn't happen, and the same entry shows as a failed call in the
     * turn's trace.
     *
     * Only fires when NO tool call was captured - a model that leaked one
     * marker but really called others is a partial success, and reporting a
     * blanket failure over it would be worse than saying nothing.
     */
    /**
     * Tidies the raw generation output now that [answer] asks for plain text
     * rather than a parsed object (2026-08-25).
     *
     * Two things the converter chain used to absorb, and one it did not:
     *  - **Thinking blocks.** `createObject` went through Embabel's
     *    `SuppressThinkingConverter`; `generateText` does not, so a reasoning
     *    model would otherwise show its scratchpad to the user. Cutting at the
     *    LAST `</think>` also handles the unclosed-opening-tag case.
     *  - **A JSON envelope emitted out of habit** by a model that has seen
     *    this shape before, optionally inside a code fence. Unwrapped only
     *    when it is *exactly* one object with one textual `text` field, so a
     *    reply that legitimately shows the user some JSON is left alone.
     *  - **A ```markdown fence around the WHOLE reply** (2026-08-28, user's
     *    own report: replies "shown as quoted text"). Instruct models wrap
     *    their output in one constantly, and index.html then renders the
     *    entire answer as a grey monospace block with its own `#` and `**`
     *    showing - nothing is formatted, because from the renderer's point
     *    of view the reply *is* one code block. The fence is a habit, not
     *    content, so it is dropped.
     *
     * That last unwrap is deliberately narrow, and the narrowness is the
     * whole safety argument:
     *  - only `markdown`/`md` info strings - a reply that is entirely one
     *    ```python block is code the user asked for and stays a code block,
     *  - only when the fence spans the whole reply, and only when nothing
     *    inside it looks like another fence, so a message that legitimately
     *    *shows* a fenced example keeps it.
     *
     * Deliberately not a general-purpose cleaner: anything broader would start
     * editing real answers.
     */
    private fun cleanAnswerText(raw: String?): String {
        // Nullable purely so this compiles against either shape of
        // generateText's return type; an empty reply is reported rather than
        // shown as an empty bubble, which reads like the app hanging.
        val text = raw ?: ""
        val withoutThinking = if (text.contains("</think>")) text.substringAfterLast("</think>") else text
        val trimmed = withoutThinking.trim()
        val fence = WHOLE_REPLY_FENCE.find(trimmed)
        val fenceInfo = fence?.groupValues?.get(1)?.trim()?.lowercase().orEmpty()
        // A body containing another fence marker means the outer match ran
        // across two separate blocks - not one fence around everything.
        val fenced = fence?.groupValues?.get(2)?.trim()?.takeUnless { it.contains("\n```") }
        val body = if (fenced != null && fenceInfo in MARKDOWN_FENCE_INFO) fenced else trimmed
        val candidate = fenced ?: trimmed
        val unwrapped = if (!candidate.startsWith("{")) {
            body
        } else {
            try {
                val node = objectMapper.readTree(candidate)
                val field = node.get("text")
                if (node.isObject && node.size() == 1 && field != null && field.isTextual) {
                    field.asText().trim()
                } else {
                    body
                }
            } catch (e: Exception) {
                body
            }
        }
        if (unwrapped.isBlank()) {
            log.warn("The generation model returned an empty reply")
            return "The model returned an empty reply. Please try again."
        }
        return unwrapped
    }

    private fun leakedToolCallFailure(request: ChatRequest, note: String?, executions: List<ToolExecution>): List<ToolExecution> {
        if (executions.isNotEmpty() || note == null) return emptyList()
        val leaked = TOOL_CALL_LEAK_MARKERS.any { note.contains(it, ignoreCase = true) }
        if (!leaked) return emptyList()
        val model = resolvedModel(request, ModelRoleKeys.DOCUMENT_EDIT, documentEditDefaultModel)
        log.warn(
            "The document-editing model ({}) emitted a tool call as plain text instead of calling the tool: \"{}\". " +
                "That model does not do native tool calling in this setup - pick one that does " +
                "(`ollama show <model>` lists \"tools\" under Capabilities) in the settings popup's " +
                "\"Document editing\" dropdown.",
            model,
            note.take(200),
        )
        return listOf(
            ToolExecution(
                tool = "document_edit",
                input = "",
                rawOutput = """{"error": "The document could not be changed: the configured document-editing model ($model) """ +
                    """cannot call tools in this setup, so the requested change was not carried out."}""",
                durationMs = 0,
            ),
        )
    }

    /**
     * Cheap pre-filter for [documentEdit]'s verb short-circuit: does this
     * message ask for anything to be changed or created at all? Same kind
     * of keyword check as [looksLikeDocumentCreation], and generous on
     * purpose - a miss silently costs the user the feature for that turn,
     * a spurious match costs one LLM call that then does nothing.
     *
     * German as well as English: the app is used in both, and a filter that
     * only knew English would switch document editing off for half its
     * users without a word in the log.
     */
    private fun looksLikeDocumentChange(message: String): Boolean {
        val lower = message.lowercase()
        return DOCUMENT_CHANGE_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Cheap pre-filter for [documentEdit]'s short-circuit: could this
     * message plausibly be asking for a NEW document, in a space that has
     * none to edit yet? Deliberately a keyword check and not a model call -
     * see that action's own doc comment. A false negative just means the
     * user has to phrase a creation request more plainly; a false positive
     * costs one LLM call that then does nothing.
     */
    private fun looksLikeDocumentCreation(message: String): Boolean {
        val lower = message.lowercase()
        val mentionsDocument = listOf("document", "docx", "word", "dokument").any { lower.contains(it) }
        val mentionsCreation = listOf(
            "create", "write", "draft", "new ", "generate", "save",
            "erstell", "schreib", "speicher",
        ).any { lower.contains(it) }
        return mentionsDocument && mentionsCreation
    }

    @AchievesGoal(description = "Return a chat reply to the user based on the insights gathered from your tools")
    @Action
    fun answer(
        results: ToolResults,
        request: ChatRequest,
        strategy: DocumentSearchStrategy,
        edits: DocumentEdits,
        context: OperationContext,
    ): ChatReply {
        log.debug("answer : {}", request.message)
        val hasToolResults = results.executions.isNotEmpty()
        val toolContext = if (!hasToolResults) {
            "No tools were needed for this message."
        } else {
            results.executions.joinToString("\n\n") {
                "Tool ${it.tool} (input: \"${it.input}\"):\n${it.rawOutput}"
            }
        }
        log.debug("ToolContext: {}", toolContext)

        // Multi-document (2026-08-22, see ChatRequest.documentIds's doc
        // comment) - every currently selected document is looked up here,
        // silently dropping any id that no longer resolves (e.g. deleted
        // between being selected client-side and this turn actually
        // running) rather than failing the whole turn over one stale id.
        val attachedDocs = request.documentIds.mapNotNull { id -> documentStore.get(id)?.let { id to it } }

        // Two-stage search (2026-08-22, see springchat3_document_qa.md in
        // space memory): [documentSearchStrategy] already decided, via its
        // own small dedicated LLM, whether this turn's question is best
        // answered from each document's own extracted outline
        // (DocumentStructureExtractor's PDF bookmarks) or by searching its
        // content - the single useStructure/useVector decision is applied
        // to every attached document independently below (see
        // DocumentSearchStrategy's doc comment for why this stays one
        // classification per turn rather than one per document). useVector
        // is forced on per document whenever useStructure ends up unusable
        // for that document (no structure extracted for it) or wasn't
        // chosen, so a document is never left with neither search running.
        // Both can end up true at once for a given document (its own
        // structure exists AND strategy.useVector was also set) -
        // documentContext below merges whichever pieces are present per
        // document rather than treating the two as mutually exclusive.
        // Timed and surfaced to the UI as its own step, sibling to
        // "Analyzing message ..."/"Document search strategy ..."/"Generating
        // answer ..." - run and finished before that step's own timer starts
        // below, so none of them overlap. Purely the search/flatten time -
        // documentSearchStrategy's own LLM call is timed and reported
        // separately as its own step (see that method's doc comment), not
        // folded in here.
        data class DocPlan(val filename: String, val structure: DocumentStructure?, val useStructure: Boolean, val useVector: Boolean)
        data class DocContext(val filename: String, val structureText: String?, val vectorSearched: Boolean, val chunks: List<Document>)

        var retrievalSummary: RetrievalSummary? = null
        var docContexts: List<DocContext> = emptyList()
        if (attachedDocs.isNotEmpty()) {
            val plans = attachedDocs.map { (id, doc) ->
                val docStructure = documentStructureStore.get(id)
                val useStructure = strategy.useStructure && docStructure != null
                val useVector = strategy.useVector || !useStructure
                id to DocPlan(doc.filename, docStructure, useStructure, useVector)
            }
            val filenames = plans.map { (_, plan) -> plan.filename }
            val via = listOfNotNull(
                "structure".takeIf { plans.any { (_, plan) -> plan.useStructure } },
                "vector".takeIf { plans.any { (_, plan) -> plan.useVector } },
            ).joinToString("+")
            progressBus.emit(request.correlationId, ChatProgressEvent.RetrievalStarted(filenames, via))
            val retrievalStart = System.currentTimeMillis()

            var resultCount = 0
            docContexts = plans.map { (id, plan) ->
                val docStructureText = if (plan.useStructure) {
                    resultCount += plan.structure!!.nodes.size
                    flattenStructure(plan.structure.nodes)
                } else {
                    null
                }
                val chunks = if (plan.useVector) {
                    documentIndex.search(id, request.message).also { resultCount += it.size }
                } else {
                    emptyList()
                }
                DocContext(plan.filename, docStructureText, plan.useVector, chunks)
            }

            val retrievalSeconds = (System.currentTimeMillis() - retrievalStart) / 1000.0
            retrievalSummary = RetrievalSummary(filenames, resultCount, retrievalSeconds, via)
            progressBus.emit(
                request.correlationId,
                ChatProgressEvent.RetrievalFinished(filenames, resultCount, retrievalSeconds, via),
            )
        }

        val answerStepName = generatingAnswerStepName(request)
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted(answerStepName))
        val start = System.currentTimeMillis()

        val documentContext = when {
            attachedDocs.isEmpty() -> "No document is attached to this conversation."
            else -> buildString {
                docContexts.forEach { doc ->
                    if (doc.structureText != null) {
                        append("\"${doc.filename}\"'s own table of contents/outline:\n\n")
                        append(doc.structureText)
                        append("\n\n")
                    }
                    if (doc.vectorSearched) {
                        if (doc.chunks.isEmpty()) {
                            append(
                                "A search of \"${doc.filename}\"'s actual content found no " +
                                    "passages relevant to this question.\n\n",
                            )
                        } else {
                            append("Passages from \"${doc.filename}\" most relevant to this question:\n\n")
                            doc.chunks.forEachIndexed { index, chunk ->
                                val page = chunk.metadata["page_number"]
                                val label = if (page != null) "Passage ${index + 1} (page $page)" else "Passage ${index + 1}"
                                append("$label:\n${chunk.text.orEmpty()}\n\n")
                            }
                        }
                    }
                }
            }
        }
        log.debug(
            "documentContext: {} chars, {} documents, {} chunks total, via {}",
            documentContext.length,
            attachedDocs.size,
            docContexts.sumOf { it.chunks.size },
            retrievalSummary?.via,
        )

        // Multi-document (2026-08-22, see ChatRequest.documentIds's doc
        // comment): since strategy.useStructure/useVector is one decision
        // applied to every attached document (see DocumentSearchStrategy's
        // doc comment), but each document's own structure availability can
        // differ, the guidance below is picked from what actually ended up
        // used across ALL attached documents together, and phrased so it
        // still reads correctly whether one document or several are
        // attached.
        val anyStructureText = docContexts.any { it.structureText != null }
        val anyVectorSearched = docContexts.any { it.vectorSearched }
        val documentGuidance = when {
            attachedDocs.isEmpty() ->
                "No document is attached to this conversation. If the user's message " +
                    "clearly needs one (e.g. asks you to summarize or find something in " +
                    "\"the document\" or \"the pdf\"), say so rather than inventing content."
            anyStructureText && anyVectorSearched ->
                "For each attached document above, you're given either its own table of " +
                    "contents, a search of its actual content, or both, labeled by filename - " +
                    "use whichever actually answers the question for that document: the " +
                    "outline for anything about a document's structure, the passages for " +
                    "anything about its substance. Draw from every attached document that's " +
                    "actually relevant to the question, not just the first one, and say " +
                    "plainly if none of them cover what was asked, rather than guessing."
            anyStructureText ->
                "The outline(s) above are each document's own table of contents, not a search " +
                    "result over its content - complete, so if one answers the user's question " +
                    "(e.g. listing modules/chapters/sections), just answer directly from it, " +
                    "naming which document it came from if more than one is attached. There's " +
                    "no page-level detail beyond what's shown, so if the user is asking about " +
                    "the substance of a section rather than just its existence or position, " +
                    "say the outline doesn't cover that rather than guessing."
            else ->
                "The passages above are the excerpts a search found most relevant to this " +
                    "specific question, labeled by which document they came from - not the " +
                    "whole document(s). If they answer what the user asked, use them, and " +
                    "mention where natural which document (and page number, if shown) you're " +
                    "drawing from, especially if more than one document is attached. If they " +
                    "don't contain what the user asked about, say so plainly rather than " +
                    "guessing or answering from unrelated general knowledge as if it came from " +
                    "an attached document - the answer may simply be in a part of a document " +
                    "this search didn't surface."
        }

        val toolErrorGuidance = if (hasToolResults) {
            """
            If any tool result is a JSON object with an "error" field,
            that tool could not provide what was asked for - tell the
            user that plainly, using the error's own message as the
            reason, rather than ignoring it, glossing over it, or
            inventing an answer as if the data were available. For
            example, if a weather tool's result says data isn't
            available for a location, tell the user clearly that you
            don't have weather information for that place and briefly
            why (e.g. it's outside the covered region) - don't guess a
            forecast.

            If a tool result specifically says it couldn't determine a
            location and asks you to find out which one the user means,
            go a step further than just explaining the error: ask the
            user which location they'd like, in plain language, rather
            than guessing a place or inventing one.
            """.trimIndent()
        } else {
            null
        }

        val formattingGuidance = """
            Format the reply text using Markdown where it genuinely
            helps readability: **bold** for key terms, `inline code`
            for commands/file names/technical tokens, fenced code
            blocks (```) for actual code or file contents, and bullet
            ("- ") or numbered ("1. ") lists for genuinely enumerable
            items - keep lists to a single level, no nested
            sub-bullets. Use a heading (## or ###) only for a longer,
            multi-section answer, such as summarizing a whole document
            section by section - never for a short, conversational
            reply. Do not use tables or blockquotes - the chat UI
            doesn't render them and they'll show up as broken,
            unreadable text. For a reply that's naturally one or two
            sentences, just write plain sentences - don't force a list
            or heading onto it just to use more formatting.

            Never wrap the whole reply in a code fence. ``` is for actual
            code, file contents or a command - not for your answer as a
            whole. A reply that opens with ```markdown is shown to the user
            as a block of raw source, with the # and ** still in it.

            This especially applies when the source material itself is
            a list - e.g. several named or numbered items such as
            modules, chapters, or steps. Use an actual list for those,
            never one long comma-separated sentence.

            Correct:
            - Module 1 – The New Era of Digital Transformation
            - Module 2 – Making Sense of Artificial Intelligence
            - Module 3 – Living and Working with Non-Human Collaborators
            """.trimIndent()

        // What documentEdit already did this turn, if anything (2026-08-23).
        // This step performs nothing itself - it has no tools at all, by
        // design (see documentEdit's own doc comment) - so this block is a
        // report of completed work, and the guidance below is about
        // describing it honestly rather than about deciding anything.
        //
        // The completeness paragraph was added 2026-08-29 after a reply that
        // opened "I have edited the stories in greeks.docx to make them
        // longer" when nothing of the sort had happened: the turn's only
        // operation was a save_answer_as_word_document that SUCCEEDED, and
        // the two older rules here cover an error and a no-op but not a
        // success at some OTHER operation than the one asked for. The model
        // saw a successful result next to the request and welded them
        // together. Since this step cannot write anything itself, a reply
        // claiming a change is the user's only evidence it happened - which
        // makes an invented one the worst thing this prompt can produce.
        // Three branches since 2026-08-29, where there used to be two. The
        // middle one - the step RAN, changed nothing, and said why - had no
        // way to exist before [DocumentEdits.note] was carried across, and
        // it is the whole reason the note now is: a question back to the
        // user lives there, and so does an honest "I did not do that, and
        // here is what I would need to know".
        val documentEditGuidance = when {
            edits.executions.isEmpty() && edits.note.isNullOrBlank() -> null

            // Ran and declined. Deliberately says "you have no tools" out
            // loud: this step's model is the same one that just held an
            // editing tool belt in documentEdit, and the reply is not the
            // place to try again.
            edits.executions.isEmpty() -> listOf(
                """
                The document-editing step ran this turn and changed NOTHING.
                No document was created, edited or renamed. These were its own
                closing words:
                """.trimIndent(),
                edits.note.orEmpty(),
                """
                Nothing in that note is a change that happened - it is that
                step explaining itself. If it says something is missing or
                ambiguous - which document was meant, which paragraph, what
                exactly to write - put that question to the user plainly, in
                your own words, and stop there. Do not guess the answer for
                them, do not attempt the change yourself, and never say a
                document was changed: you have no tools here, so nothing you
                write alters a file.
                """.trimIndent(),
                """
                If instead that note DESCRIBES what it would do - names tools,
                lists steps, says "I would use" or explains how the change
                could be made - then it is neither a question nor a plan for
                the user to carry out. It means the editing step failed to
                act. Say so in one or two plain sentences: the change was not
                made, and they can ask again. Do not repeat the tool names,
                do not reproduce the steps, and do not present any of it as
                something they could do - those tools exist only inside this
                app and the user cannot call them.

                Its claims about what a document contains are not evidence
                either. That step may never have looked, so do not repeat a
                statement like "the document has no headings" as fact - if
                the user asked about the document's contents, answer from the
                passages above, which came from the document itself.
                """.trimIndent(),
            ).joinToString("\n\n")

            else -> {
                val outcomes = edits.executions.joinToString("\n") { "- ${it.tool} (input: \"${it.input}\"): ${it.rawOutput}" }
                // Assembled as separate paragraphs rather than one raw string
                // with the outcomes interpolated into it - the trap documentEdit's
                // own prompt already learned (springchat3_document_edit_prompt_scope
                // in project memory): trimIndent runs AFTER interpolation, so a
                // multi-line value whose later lines start at column 0 makes the
                // shallowest indent 0 and silently disables the trim for the
                // whole block.
                listOfNotNull(
                    """
                    These are the ONLY operations performed on the user's documents
                    this turn, each with its own result:
                    """.trimIndent(),
                    outcomes,
                    """
                    Tell the user plainly what happened, in one sentence, as part of
                    your reply. Report what the results above actually say - if one
                    of them is an error or says nothing was changed, say that
                    instead of claiming the change succeeded. Do not offer to make a
                    change that is listed above: it has already been made.
                    """.trimIndent(),
                    """
                    That list is complete. If the user asked for something which is
                    not in it - a document to be changed, created or renamed that no
                    result above mentions - then it did NOT happen, and you must say
                    so plainly, naming that document, rather than describing it as
                    done. A successful operation above is evidence for itself only,
                    never for some other thing the user also asked for. Writing the
                    requested text into your reply is not the same as changing a
                    document, and must never be described as if it were.
                    """.trimIndent(),
                    // Appended by concatenation, not interpolated into the block
                    // above it, for the trimIndent reason spelled out further up.
                    edits.note?.takeIf { it.isNotBlank() }?.let { note ->
                        """
                        The editing step also signed off with the following. It is
                        commentary, not a record: the results above are what
                        actually happened, and where the two disagree the results
                        win. A note claiming a change that no result above shows is
                        simply wrong - do not repeat it.
                        """.trimIndent() + "\n\n" + note
                    },
                ).joinToString("\n\n")
            }
        }

        val prompt = listOfNotNull(
            """
            The user's message was: "${request.message}"

            Raw results from any tools that were run:
            $toolContext

            $documentContext

            Write a helpful, concise reply to the user's message, using
            the tool results and any attached documents where relevant.
            If no tool results were needed or gathered and no documents
            are attached, just answer directly.
            """.trimIndent(),
            formattingGuidance,
            documentGuidance,
            documentEditGuidance,
            toolErrorGuidance,
            "Reply with the answer itself and nothing else - no JSON, no " +
                "wrapper object, no surrounding code fence. Markdown inside " +
                "the answer is expected, per the instructions above.",
        ).joinToString("\n\n")
        log.trace("chat llm answer prompt:\n{}", prompt)

        // Single LLM call, straight from the raw tool output - no separate
        // summarize/draft/review passes. The (larger) generation model is
        // trusted to both pick out what's relevant and write the final reply
        // in one shot.
        //
        // generateText, NOT createObject (2026-08-25, after a real failure -
        // the user's log showed ten retries of a perfectly good Markdown
        // summary being rejected for not being JSON, each retry a fresh
        // generation-model call, and the converter chain turning the
        // rejected text into "" so the reported cause was the useless "No
        // content to map due to end-of-input"). The target was
        // `AnswerText(val text: String)` - a single string - so the JSON
        // envelope bought nothing and cost a whole class of failure. Same
        // lesson documentEdit already learned; see its own comment.
        val answered = cleanAnswerText(
            llmForRole(request, context, ModelRoleKeys.GENERATION).generateText(prompt),
        )

        val seconds = (System.currentTimeMillis() - start) / 1000.0
        progressBus.emit(request.correlationId, ChatProgressEvent.StepFinished(answerStepName, seconds))
        // Both steps' tool calls - analyzeMessage's own gathering calls plus
        // whatever documentEdit did (2026-08-23) - so an edit shows in the
        // trace beside the lookups that led to it. Tagged with the step each
        // one belongs to since 2026-08-28, because they were all being drawn
        // under "Analyzing message ..." regardless of which step made them;
        // documentEdit's also carry their result, which is the one thing the
        // user actually wants to read there - it says what happened to their
        // document.
        // Both lists carry their step, so a null one means "recorded before
        // this existed" and nothing else - which is what lets the UI tell a
        // legacy trace apart from a step that really changed nothing.
        val toolCalls = results.executions.map { toolCallSummary(it, step = ANALYZE_STEP_NAME) } +
            edits.executions.map {
                toolCallSummary(it, step = documentEditStepName(request), outcome = editOutcome(it.rawOutput))
            }
        // Steps accumulated from gatherInfo (analyzeMessage), this turn's
        // documentSearchStrategy time (only when at least one document was
        // actually attached - same visibility rule as the retrieval row
        // itself), and this step's own time - the full pipeline timeline
        // for the UI. Order here is what the *finished* trace always shows,
        // regardless of the two document-related actions' actual concurrent
        // runtime order - see documentSearchStrategy's own doc comment.
        val documentSearchStrategyTiming = if (request.documentIds.isNotEmpty()) {
            listOf(StepTiming(documentSearchStrategyStepName(request), strategy.seconds))
        } else {
            emptyList()
        }
        // Only shown when documentEdit actually ran an LLM call - it reports
        // 0.0 seconds when it short-circuited, same honesty rule the
        // strategy row above follows.
        val documentEditTiming = if (edits.seconds > 0.0) {
            listOf(StepTiming(documentEditStepName(request), edits.seconds))
        } else {
            emptyList()
        }
        val steps = results.timings + documentSearchStrategyTiming + documentEditTiming + StepTiming(answerStepName, seconds)
        /*
         * The state the NEXT turn needs to understand a bare "yes"
         * (2026-08-29). Deliberately the same condition the middle branch of
         * documentEditGuidance above already tests - the editing step ran,
         * changed nothing, and explained itself - because that IS the "I
         * asked you something" state. Nothing new has to be detected, and in
         * particular nothing has to guess whether the note was phrased as a
         * question: a step that declined and said why is worth answering
         * either way.
         *
         * [request.message] is stored beside it because the reply alone is
         * not enough. "yes" is only actionable next to the request it agrees
         * to - see [PendingEdit].
         */
        val declinedNote = edits.note?.takeIf { it.isNotBlank() }
        val pendingEdit = if (edits.executions.isEmpty() && declinedNote != null) {
            PendingEdit(question = declinedNote, askedAbout = request.message)
        } else {
            null
        }
        val reply = ChatReply(answered, toolCalls, steps, retrievalSummary, pendingEdit)
        // Terminal event for the live stream - ChatController's /chat/stream
        // endpoint could emit this itself once AgentInvocation.invoke(...)
        // returns the same reply, but emitting it here means answer, not
        // the controller, stays the one place that decides the turn is done.
        progressBus.emit(request.correlationId, ChatProgressEvent.Done(reply))

        return reply
    }
}

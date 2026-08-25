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
import com.fasterxml.jackson.databind.ObjectMapper

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
        request.modelOverrides[role]
            ?.let { context.ai().withLlm(it) }
            ?: context.ai().withLlmByRole(role)

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
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted("Analyzing message ..."))
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
        progressBus.emit(request.correlationId, ChatProgressEvent.StepFinished("Analyzing message ...", seconds))

        return ToolResults(executions, timings = listOf(StepTiming("Analyzing message ...", seconds)))
    }

    /**
     * Best-effort heuristic shared by [ToolCallProgressBridge] (for the live
     * [ChatProgressEvent.ToolFinished] event) and the final
     * [ToolCallSummary.failed] built below - see [ToolCallSummary]'s doc
     * comment in ChatModel.kt for why this is a heuristic rather than a hard
     * guarantee.
     */
    private fun isToolError(output: String): Boolean = output.trimStart().startsWith("{\"error\"")

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
     * when editing is switched off, when no project is active, or when the
     * active project has no Word documents to edit and the message is
     * clearly not asking for one to be created. The last of those is a
     * cheap keyword pre-filter rather than a classifier call: getting it
     * wrong costs one skipped feature invocation, and the alternative (an
     * LLM call on every single turn just to rule editing out) costs more
     * than the feature is worth.
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
            log.debug("documentEdit skipped: no active project, so there is nothing this step could reach")
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
                "documentEdit skipped: none of the {} Word document(s) in project {} are selected, and \"{}\" " +
                    "doesn't look like a request to create one",
                wordDocumentWorkspace.list(request.spaceId).size,
                request.spaceId,
                request.message,
            )
            return DocumentEdits()
        }
        log.debug(
            "documentEdit running against {} selected Word document(s) in project {}",
            documents.size,
            request.spaceId,
        )

        val stepName = documentEditStepName(request)
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted(stepName))
        val start = System.currentTimeMillis()

        // Every document listed here is one the user selected - the list is
        // already filtered above - so there is no "<- selected" annotation to
        // make any more. What the prompt still has to do is stop the model
        // guessing between SEVERAL selected documents; WordDocumentEditTool
        // enforces that in code too (see its targetedByUser), because this
        // paragraph is advice and that is a rule.
        val documentNames = documents.joinToString("\n") { doc -> "- \"${doc.filename}\"" }
        val prompt = """
            The user's message was: "${request.message}"

            The Word documents the user has selected in the side panel are
            the only ones you may read or change:
            ${documentNames.ifBlank { "(none - the user has selected no document)" }}

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

            Be certain WHICH document you are changing. Use the one the
            user named. If they named none and exactly one is listed above,
            use that one. If several are listed and they named none, do not
            pick one - change nothing and say which documents they could
            have meant, so they can say which. Editing the wrong document is
            worse than asking.

            Then reply with a one-sentence note stating what you changed, or
            that no change was needed.
        """.trimIndent()

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
            ) +
            // Scoped to the same selection as the editing tool: whoever is
            // about to change a document should not be able to read one it
            // isn't allowed to change. analyzeMessage's own read tool stays
            // project-wide - answering a question is a different job.
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
        return DocumentEdits(executions + leakedToolCallFailure(request, note, executions), seconds)
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
        val fenced = Regex("^```[a-zA-Z]*\\s*\\n(.*)\\n```$", RegexOption.DOT_MATCHES_ALL)
            .find(trimmed)?.groupValues?.get(1)?.trim()
        val candidate = fenced ?: trimmed
        val unwrapped = if (!candidate.startsWith("{")) {
            trimmed
        } else {
            try {
                val node = objectMapper.readTree(candidate)
                val field = node.get("text")
                if (node.isObject && node.size() == 1 && field != null && field.isTextual) {
                    field.asText().trim()
                } else {
                    trimmed
                }
            } catch (e: Exception) {
                trimmed
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
     * Cheap pre-filter for [documentEdit]'s short-circuit: could this
     * message plausibly be asking for a NEW document, in a project that has
     * none to edit yet? Deliberately a keyword check and not a model call -
     * see that action's own doc comment. A false negative just means the
     * user has to phrase a creation request more plainly; a false positive
     * costs one LLM call that then does nothing.
     */
    private fun looksLikeDocumentCreation(message: String): Boolean {
        val lower = message.lowercase()
        val mentionsDocument = listOf("document", "docx", "word", "dokument").any { lower.contains(it) }
        val mentionsCreation = listOf("create", "write", "draft", "new ", "generate", "erstell", "schreib").any { lower.contains(it) }
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
        // project memory): [documentSearchStrategy] already decided, via its
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
        val documentEditGuidance = if (edits.executions.isEmpty()) {
            null
        } else {
            val outcomes = edits.executions.joinToString("\n") { "- ${it.tool} (input: \"${it.input}\"): ${it.rawOutput}" }
            """
            You have already changed the user's documents this turn. These
            were the changes, with each tool's own result:

            $outcomes

            Tell the user plainly what was changed, in one sentence, as part
            of your reply. Report what the results above actually say - if
            one of them is an error or says nothing was changed, say that
            instead of claiming the change succeeded. Do not offer to make
            the change: it has already been made.
            """.trimIndent()
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
        // trace beside the lookups that led to it.
        val toolCalls = (results.executions + edits.executions).map {
            ToolCallSummary(
                tool = it.tool,
                input = it.input,
                failed = isToolError(it.rawOutput),
                seconds = it.durationMs / 1000.0,
            )
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
        val reply = ChatReply(answered, toolCalls, steps, retrievalSummary)
        // Terminal event for the live stream - ChatController's /chat/stream
        // endpoint could emit this itself once AgentInvocation.invoke(...)
        // returns the same reply, but emitting it here means answer, not
        // the controller, stays the one place that decides the turn is done.
        progressBus.emit(request.correlationId, ChatProgressEvent.Done(reply))

        return reply
    }
}

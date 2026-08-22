package ch.arcticsoft.springchat3.agent

import ch.arcticsoft.springchat3.document.DocumentIndex
import ch.arcticsoft.springchat3.document.DocumentStore
import ch.arcticsoft.springchat3.document.DocumentStructureStore
import ch.arcticsoft.springchat3.document.StructureNode
import ch.arcticsoft.springchat3.settings.AppSettingsStore
import ch.arcticsoft.springchat3.settings.ModelRoleKeys
import ch.arcticsoft.springchat3.tools.ChatToolRegistry
import ch.arcticsoft.springchat3.tools.CurrentLocationTool
import ch.arcticsoft.springchat3.tools.GeoTool
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
    private val appSettingsStore: AppSettingsStore,
    @Value("\${embabel.models.llms.generation}") private val generationDefaultModel: String,
    @Value("\${embabel.models.llms.document-search-strategy}") private val documentSearchStrategyDefaultModel: String,
) {
    val objectMapper = ObjectMapper()
    private val log = LoggerFactory.getLogger(ChatAgent::class.java)

    /**
     * Resolves the LLM for tool selection - [ModelRoleKeys.TOOL_SELECTION]'s
     * override if the user picked one in the settings popup (2026-08-22, see
     * springchat3_settings.md in project memory), else Embabel's own
     * `default-llm` (`Ai.withDefaultLlm()`, unchanged from before this
     * feature). A separate helper from [llmForRole] because tool selection
     * has no real Embabel role name to fall back to - it's `default-llm`,
     * not a `embabel.models.llms.*` entry.
     */
    private fun toolSelectionLlm(context: OperationContext) =
        appSettingsStore.get().modelOverrides[ModelRoleKeys.TOOL_SELECTION]
            ?.let { context.ai().withLlm(it) }
            ?: context.ai().withDefaultLlm()

    /**
     * Resolves the LLM for [role] (one of [ModelRoleKeys.GENERATION]/
     * [ModelRoleKeys.DOCUMENT_SEARCH_STRATEGY], both real Embabel role names
     * matching `embabel.models.llms.*`) - the user's override for that role
     * if one is set, else `Ai.withLlmByRole(role)` exactly as before this
     * feature. Embabel itself has no supported way to change a role's
     * configured model at runtime (`embabel.models.*` is bound once at
     * startup, see [AppSettingsStore]'s own doc comment) - `Ai.withLlm(exact
     * model name)` sidesteps that entirely by naming the model directly
     * rather than going through the role indirection, which works because
     * embabel-agent-ollama-autoconfigure already registers every locally
     * pulled Ollama model as its own selectable LLM by tag.
     */
    private fun llmForRole(context: OperationContext, role: String) =
        appSettingsStore.get().modelOverrides[role]
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
    private fun resolvedModel(role: String, default: String): String =
        appSettingsStore.get().modelOverrides[role] ?: default

    /**
     * [documentSearchStrategy]'s trace/progress label, including the exact
     * model actually deciding this turn's strategy - recomputed (rather than
     * a stored constant, now that it's no longer a fixed string) in both
     * [documentSearchStrategy] itself and [answer]'s `steps` list, which is
     * safe since [AppSettingsStore]'s settings can't change mid-request; a
     * single function keeps both call sites from silently drifting apart the
     * way a plain constant used to.
     */
    private fun documentSearchStrategyStepName(): String =
        "Document search strategy (${resolvedModel(ModelRoleKeys.DOCUMENT_SEARCH_STRATEGY, documentSearchStrategyDefaultModel)}) ..."

    /** Same idea as [documentSearchStrategyStepName], for [answer]'s own generation step. */
    private fun generatingAnswerStepName(): String =
        "Generating answer (${resolvedModel(ModelRoleKeys.GENERATION, generationDefaultModel)}) ..."

    /**
     * Short-circuits to no LLM call at all when tool use is switched off via
     * the settings popup (2026-08-22, see springchat3_settings.md in project
     * memory, [AppSettingsStore]) - with no tools to hand the model, there is
     * nothing left for this step to decide, so skip the round-trip entirely
     * rather than calling with an empty tool list. Emits no progress events
     * either, so "Analyzing message ..." simply doesn't appear in the trace
     * for that turn - same "don't show a step that did nothing" convention
     * [documentSearchStrategy] already uses for its own no-op short-circuits.
     */
    @Action
    fun analyzeMessage(request: ChatRequest, context: OperationContext): ToolResults {
        if (!appSettingsStore.get().toolsEnabled) {
            return ToolResults(emptyList())
        }

        log.debug("analyze message: {}", request.message)
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted("Analyzing message ..."))
        val start = System.currentTimeMillis()

        val currentLocationTool = CurrentLocationTool(geoTool, request.latitude, request.longitude)
        val toolObjects = chatToolRegistry.tools() + currentLocationTool
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
        val documentNote = request.documentId
            ?.let { documentStore.get(it) }
            ?.let {
                "An attached document, \"${it.filename}\", is available for this " +
                    "conversation - questions about it (e.g. \"summarize it\", \"what does " +
                    "it say about X\") are answered from its content by a later step, not by " +
                    "any tool here. If the message is about the attached document, no tool " +
                    "call is needed for that."
            }
            ?: "No document is attached to this conversation."

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
            toolSelectionLlm(context)
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
        val documentId = request.documentId
            ?: return DocumentSearchStrategy(useStructure = false, useVector = false)

        val stepName = documentSearchStrategyStepName()
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted(stepName))
        val start = System.currentTimeMillis()

        val structure = documentStructureStore.get(documentId)
        val strategy = if (structure == null) {
            DocumentSearchStrategy(useStructure = false, useVector = true)
        } else {
            val prompt = """
                The user's question was: "${request.message}"

                This document has the following table of contents/outline:

                ${flattenStructure(structure.nodes)}

                Decide, independently, whether answering this question needs
                each of these two sources:

                1. The outline above (structure) - useful when the question
                   is about the document's own organization: what sections,
                   chapters, or modules it has, how many there are, their
                   titles, or where in the document something is located.
                2. A search of the document's actual written content (vector)
                   - needed whenever answering requires what the document
                   actually SAYS rather than how it's organized: facts,
                   arguments, numbers, definitions, or anything else found in
                   the body text, not the outline.

                Always set "useVector" to true for any request to summarize
                the document (or a part of it), to explain something in it or
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
                val classification = llmForRole(context, ModelRoleKeys.DOCUMENT_SEARCH_STRATEGY)
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

    @AchievesGoal(description = "Return a chat reply to the user based on the insights gathered from your tools")
    @Action
    fun answer(results: ToolResults, request: ChatRequest, strategy: DocumentSearchStrategy, context: OperationContext): ChatReply {
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

        val documentId = request.documentId
        val attachedDoc = documentId?.let { documentStore.get(it) }
        val structure = documentId?.let { documentStructureStore.get(it) }
        // Two-stage search (2026-08-22, see springchat3_document_qa.md in
        // project memory): [documentSearchStrategy] already decided, via its
        // own small dedicated LLM, whether this question is best answered
        // from the document's own extracted outline (documentOutline - PDF
        // bookmarks, see DocumentStructureExtractor) or by searching its
        // content. useVector is forced on whenever useStructure ends up
        // unusable (structure null - most documents, since not every PDF has
        // embedded bookmarks - even if the classification wanted it) or
        // wasn't chosen, so a document question is never left with neither
        // search running. Both can end up true at once (structure genuinely
        // exists AND strategy.useVector was also set) - answer() below
        // merges whichever pieces are present into documentContext rather
        // than treating the two as mutually exclusive, ready for a future
        // version of documentSearchStrategy that deliberately asks for both
        // in the same turn (see DocumentSearchStrategy's doc comment).
        // Timed and surfaced to the UI as its own step, sibling to
        // "Analyzing message ..."/"Document search strategy ..."/"Generating
        // answer ..." - run and finished before that step's own timer starts
        // below, so none of them overlap. Purely the search/flatten time -
        // documentSearchStrategy's own LLM call is timed and reported
        // separately as its own step (see that method's doc comment), not
        // folded in here.
        var retrievalSummary: RetrievalSummary? = null
        var relevantChunks: List<Document> = emptyList()
        var structureText: String? = null
        var vectorSearched = false
        if (documentId != null && attachedDoc != null) {
            val useStructure = strategy.useStructure && structure != null
            val useVector = strategy.useVector || !useStructure
            vectorSearched = useVector
            val via = listOfNotNull("structure".takeIf { useStructure }, "vector".takeIf { useVector }).joinToString("+")
            progressBus.emit(request.correlationId, ChatProgressEvent.RetrievalStarted(attachedDoc.filename, via))
            val retrievalStart = System.currentTimeMillis()
            if (useStructure) {
                structureText = flattenStructure(structure!!.nodes)
            }
            if (useVector) {
                relevantChunks = documentIndex.search(documentId, request.message)
            }
            val resultCount = (if (useStructure) structure!!.nodes.size else 0) + (if (useVector) relevantChunks.size else 0)
            val retrievalSeconds = (System.currentTimeMillis() - retrievalStart) / 1000.0
            retrievalSummary = RetrievalSummary(attachedDoc.filename, resultCount, retrievalSeconds, via)
            progressBus.emit(
                request.correlationId,
                ChatProgressEvent.RetrievalFinished(attachedDoc.filename, resultCount, retrievalSeconds, via),
            )
        }

        val answerStepName = generatingAnswerStepName()
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted(answerStepName))
        val start = System.currentTimeMillis()

        val documentContext = when {
            attachedDoc == null -> "No document is attached to this conversation."
            else -> buildString {
                if (structureText != null) {
                    append("The attached document's (\"${attachedDoc.filename}\") own table of contents/outline:\n\n")
                    append(structureText)
                    append("\n\n")
                }
                if (vectorSearched) {
                    if (relevantChunks.isEmpty()) {
                        append(
                            "A search of the attached document's (\"${attachedDoc.filename}\") actual " +
                                "content found no passages relevant to this question.\n\n",
                        )
                    } else {
                        append("Passages from the attached document (\"${attachedDoc.filename}\") most relevant to this question:\n\n")
                        relevantChunks.forEachIndexed { index, chunk ->
                            val page = chunk.metadata["page_number"]
                            val label = if (page != null) "Passage ${index + 1} (page $page)" else "Passage ${index + 1}"
                            append("$label:\n${chunk.text.orEmpty()}\n\n")
                        }
                    }
                }
            }
        }
        log.debug("documentContext: {} chars, {} chunks, via {}", documentContext.length, relevantChunks.size, retrievalSummary?.via)

        val documentGuidance = when {
            attachedDoc == null ->
                "No document is attached to this conversation. If the user's message " +
                    "clearly needs one (e.g. asks you to summarize or find something in " +
                    "\"the document\" or \"the pdf\"), say so rather than inventing content."
            structureText != null && vectorSearched ->
                "Both the document's own table of contents (above) and a search of its " +
                    "actual content are provided. Use whichever actually answers the " +
                    "question - the outline for anything about the document's structure, " +
                    "the passages for anything about its substance - and say plainly if " +
                    "neither covers what was asked, rather than guessing."
            structureText != null ->
                "The outline above is the document's own table of contents, not a search " +
                    "result over its content - it's complete, so if it answers the user's " +
                    "question (e.g. listing modules/chapters/sections), just answer directly " +
                    "from it. It has no page-level detail beyond what's shown, so if the user " +
                    "is asking about the substance of a section rather than just its existence " +
                    "or position, say the outline doesn't cover that rather than guessing."
            else ->
                "The passages above are the excerpts a search found most relevant to this " +
                    "specific question - not the whole document. If they answer what the user " +
                    "asked, use them, and mention where natural that you're drawing from the " +
                    "uploaded document (citing a page number if one is shown). If they don't " +
                    "contain what the user asked about, say so plainly rather than guessing " +
                    "or answering from unrelated general knowledge as if it came from the " +
                    "document - the answer may simply be in a part of the document this " +
                    "search didn't surface."
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

        val prompt = listOfNotNull(
            """
            The user's message was: "${request.message}"

            Raw results from any tools that were run:
            $toolContext

            $documentContext

            Write a helpful, concise reply to the user's message, using
            the tool results and the attached document (if any) where
            relevant. If no tool results were needed or gathered and no
            document is attached, just answer directly.
            """.trimIndent(),
            formattingGuidance,
            documentGuidance,
            toolErrorGuidance,
            "Respond with raw JSON only: one object with a single \"text\" " +
                "field holding your reply (formatted as Markdown per the " +
                "instructions above). Do not wrap the JSON object itself in " +
                "markdown code fences or backticks, and do not add any other " +
                "text before or after it.",
        ).joinToString("\n\n")
        log.trace("chat llm answer prompt:\n{}", prompt)

        // Single LLM call, straight from the raw tool output - no separate
        // summarize/draft/review passes. The (larger) generation model is
        // trusted to both pick out what's relevant and write the final reply
        // in one shot.
        val answered = llmForRole(context, ModelRoleKeys.GENERATION)
            .createObject(prompt, AnswerText::class.java)

        val seconds = (System.currentTimeMillis() - start) / 1000.0
        progressBus.emit(request.correlationId, ChatProgressEvent.StepFinished(answerStepName, seconds))
        val toolCalls = results.executions.map {
            ToolCallSummary(
                tool = it.tool,
                input = it.input,
                failed = isToolError(it.rawOutput),
                seconds = it.durationMs / 1000.0,
            )
        }
        // Steps accumulated from gatherInfo (analyzeMessage), this turn's
        // documentSearchStrategy time (only when a document was actually
        // attached - same visibility rule as the retrieval row itself), and
        // this step's own time - the full pipeline timeline for the UI.
        // Order here is what the *finished* trace always shows, regardless
        // of the two document-related actions' actual concurrent runtime
        // order - see documentSearchStrategy's own doc comment.
        val documentSearchStrategyTiming = if (documentId != null) {
            listOf(StepTiming(documentSearchStrategyStepName(), strategy.seconds))
        } else {
            emptyList()
        }
        val steps = results.timings + documentSearchStrategyTiming + StepTiming(answerStepName, seconds)
        val reply = ChatReply(answered.text, toolCalls, steps, retrievalSummary)
        // Terminal event for the live stream - ChatController's /chat/stream
        // endpoint could emit this itself once AgentInvocation.invoke(...)
        // returns the same reply, but emitting it here means answer, not
        // the controller, stays the one place that decides the turn is done.
        progressBus.emit(request.correlationId, ChatProgressEvent.Done(reply))

        return reply
    }
}

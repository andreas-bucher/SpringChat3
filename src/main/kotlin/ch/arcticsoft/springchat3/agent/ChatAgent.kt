package ch.arcticsoft.springchat3.agent

import ch.arcticsoft.springchat3.tools.ChatToolRegistry
import ch.arcticsoft.springchat3.tools.CurrentLocationTool
import ch.arcticsoft.springchat3.tools.GeoTool
import ch.arcticsoft.springchat3.web.ChatController
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.method.MethodToolCallbackProvider
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
) {
    companion object {
        /**
         * Role name resolved via embabel.models.llms.generation (see
         * application.yml). Used only for `answer` - `gatherInfo` uses
         * withDefaultLlm() instead.
         */
        const val GENERATION_LLM_ROLE = "generation"
    }
    val objectMapper = ObjectMapper()
    private val log = LoggerFactory.getLogger(ChatAgent::class.java)

    @Action
    fun analyzeMessage(request: ChatRequest, context: OperationContext): ToolResults {

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

        val analyzeMessagePrompt = """
            The user's message was: "${request.message}"

            Call whichever of your available tools, if any, would
            help answer it - you may call more than one, or none at
            all for a message like "hello" or something you already
            know without any tool's help.

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
        log.debug("analyzeMessage prompt (tool identification):\n{}", analyzeMessagePrompt)
        val processId = context.agentProcess.id
        log.debug("analyzeMessage processId (context.agentProcess.id): {}", processId)
        val (note, executions) = toolCallBridge.withCapture(processId, request.correlationId) {
            context.ai()
                .withDefaultLlm()
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
            log.debug("Executions {}: \n{}", it.tool, json)
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

    @AchievesGoal(description = "Return a chat reply to the user based on the insights gathered from your tools")
    @Action
    fun answer(results: ToolResults, request: ChatRequest, context: OperationContext): ChatReply {

        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted("Generating answer ..."))
        val start = System.currentTimeMillis()

        val toolContext = if (results.executions.isEmpty()) {
            "No tools were needed for this message."
        } else {
            results.executions.joinToString("\n\n") {
                "Tool ${it.tool} (input: \"${it.input}\"):\n${it.rawOutput}"
            }
        }
        log.debug("ToolContext: {}", toolContext)
        // Single LLM call, straight from the raw tool output - no separate
        // summarize/draft/review passes. The (larger) generation model is
        // trusted to both pick out what's relevant and write the final reply
        // in one shot.
        val answered = context.ai()
            .withLlmByRole(GENERATION_LLM_ROLE)
            .createObject(
                """
                The user's message was: "${request.message}"

                Raw results from any tools that were run:
                $toolContext

                Write a helpful, concise reply to the user's message, using
                the tool results where relevant. If no tool results were
                needed or gathered, just answer directly.

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

                Respond with raw JSON only. Do not wrap it in markdown code
                fences or backticks, and do not add any other text.
                """.trimIndent(),
                AnswerText::class.java,
            )

        val seconds = (System.currentTimeMillis() - start) / 1000.0
        progressBus.emit(request.correlationId, ChatProgressEvent.StepFinished("Generating answer ...", seconds))
        val toolCalls = results.executions.map {
            ToolCallSummary(
                tool = it.tool,
                input = it.input,
                failed = isToolError(it.rawOutput),
                seconds = it.durationMs / 1000.0,
            )
        }
        // Steps accumulated from gatherInfo, plus this step's own time - the
        // full pipeline timeline for the UI.
        val steps = results.timings + StepTiming("Generating answer ...", seconds)
        val reply = ChatReply(answered.text, toolCalls, steps)
        // Terminal event for the live stream - ChatController's /chat/stream
        // endpoint could emit this itself once AgentInvocation.invoke(...)
        // returns the same reply, but emitting it here means answer, not
        // the controller, stays the one place that decides the turn is done.
        progressBus.emit(request.correlationId, ChatProgressEvent.Done(reply))

        return reply
    }
}

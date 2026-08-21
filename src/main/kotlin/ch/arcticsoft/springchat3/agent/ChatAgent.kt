package ch.arcticsoft.springchat3.agent

import ch.arcticsoft.springchat3.tools.TransportTool
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext

/** Just the reply text - what the single answering LLM call is actually asked to produce. */
data class AnswerText(val text: String)

/**
 * Answers a chat message in three steps, one LLM call for the reply itself:
 *
 *   ChatRequest --[planTools2]--> ToolPlan
 *   ToolPlan + ChatRequest --[executeTools2]--> ToolResults
 *   ToolResults + ChatRequest --[answer, @AchievesGoal]--> ChatReply
 *
 * No intent classification and no separate tool-result-summarization pass -
 * planTools2 plans straight off the raw message, and answer hands the LLM
 * every tool's raw output directly rather than a pre-digested summary,
 * trusting the (larger) generation model to pull out what's relevant itself
 * in the same call that writes the reply. That's the whole simplification
 * this agent is built around: as few LLM round-trips per chat turn as
 * possible, at the cost of the generation model seeing more raw/noisy input
 * than a separate draft-then-review pass would give it.
 *
 * planTools2 only decides *whether* PUBLIC_TRANSPORT is relevant and what
 * input to call it with; the actual work happens as a plain, deterministic
 * HTTP call in executeTools2 (via [transportTool] - no extra LLM
 * round-trip). PUBLIC_TRANSPORT is the only tool left: the LOCATION and
 * WEATHER/WEATHER_FORECAST tools (and the GeoTool/WeatherTool/
 * SwissWeatherTool beans and the location-resolution step they needed) have
 * been removed entirely, by request - see git history for that machinery if
 * it's ever needed again.
 *
 * Two model tiers: planTools2 uses context.ai().withDefaultLlm() -
 * embabel.models.default-llm (a small model good at structured JSON output
 * and tool-call planning); answer - the step
 * that actually writes the reply text the user reads - uses
 * context.ai().withLlmByRole(GENERATION_LLM_ROLE) instead, resolved via
 * embabel.models.llms.generation.
 *
 * Each of the three @Action methods times itself and appends a [StepTiming]
 * to a list that's carried forward on the object it returns (ToolPlan.timings
 * -> ToolResults.timings -> ChatReply.steps), since Embabel has no separate
 * step-by-step-timing hook of its own - by the time `answer` runs, its
 * result carries the full pipeline timeline for the UI, the agent-level
 * equivalent of the per-tool timing already shown via ToolCallSummary.seconds.
 *
 * Those same steps (plus each individual tool call) are also pushed live to
 * the browser as they happen, via [progressBus] and
 * [request.correlationId][ChatRequest.correlationId] - see ChatProgress.kt
 * for the event types and ChatController's `/chat/stream` endpoint for how
 * they reach the browser. This is deliberately a second, parallel way of
 * reporting the same information (rather than, say, having the frontend
 * derive live progress from ChatReply.steps somehow) because ChatReply only
 * exists once `answer` has already finished - there's nothing to stream
 * incrementally from the final result object itself.
 */
@Agent(description = "Answers a chat message: plan whether public transport info is needed, fetch it if so, answer in one LLM call from the raw result")
class ChatAgent2(
    private val transportTool: TransportTool,
    private val progressBus: ChatProgressBus,
) {

    companion object {
        /**
         * Role name resolved via embabel.models.llms.generation (see
         * application.yml). Used only for `answer` - every other @Action
         * here uses withDefaultLlm() instead.
         */
        const val GENERATION_LLM_ROLE = "generation"
    }

    @Action
    fun planTools2(request: ChatRequest, context: OperationContext): ToolPlan {
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted("Plan tools"))
        val start = System.currentTimeMillis()
        val plan = context.ai()
            .withDefaultLlm()
            .createObject(
                """
                The user's message was: "${request.message}"

                Decide whether this tool would help answer it:
                - PUBLIC_TRANSPORT: Swiss public transport - pass a single
                  station name for next departures, or "<from> -> <to>" for a
                  connection search between two stations

                Only include it if it's actually needed - for a message like
                "hello" or something unrelated to Swiss public transport,
                return an empty list. If you do include it, give the exact
                input text it should be called with (a station name, or
                "<from> -> <to>") and a short reason.

                Respond with raw JSON only. Do not wrap it in markdown code
                fences or backticks, and do not add any other text.
                """.trimIndent(),
                ToolPlan::class.java,
            )
        val seconds = (System.currentTimeMillis() - start) / 1000.0
        progressBus.emit(request.correlationId, ChatProgressEvent.StepFinished("Plan tools", seconds))
        return plan.copy(timings = listOf(StepTiming("Plan tools", seconds)))
    }

    @Action
    fun executeTools2(plan: ToolPlan, request: ChatRequest): ToolResults {
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted("Execute tools"))
        val start = System.currentTimeMillis()
        val executions = plan.calls.mapIndexed { index, call ->
            progressBus.emit(request.correlationId, ChatProgressEvent.ToolStarted(index, call.tool, call.query))
            val callStart = System.currentTimeMillis()
            val output = runCatching {
                when (call.tool) {
                    ToolName.PUBLIC_TRANSPORT -> transportTool.query(call.query)
                }
            }.getOrElse { e -> """{"error": "${call.tool} failed: ${e.message}"}""" }
            val durationMs = System.currentTimeMillis() - callStart
            progressBus.emit(
                request.correlationId,
                ChatProgressEvent.ToolFinished(index, call.tool, call.query, durationMs / 1000.0, isToolError(output)),
            )
            ToolExecution(call.tool, call.query, output, durationMs)
        }
        val seconds = (System.currentTimeMillis() - start) / 1000.0
        progressBus.emit(request.correlationId, ChatProgressEvent.StepFinished("Execute tools", seconds))
        return ToolResults(executions, timings = plan.timings + StepTiming("Execute tools", seconds))
    }

    /**
     * Best-effort heuristic shared by the live [ChatProgressEvent.ToolFinished]
     * event and the final [ToolCallSummary.failed] built in [answer] - see
     * [ToolCallSummary]'s doc comment in ChatModel.kt for why this is a
     * heuristic rather than a hard guarantee.
     */
    private fun isToolError(output: String): Boolean = output.trimStart().startsWith("{\"error\"")

    @AchievesGoal(description = "Return a chat reply to the user")
    @Action
    fun answer(results: ToolResults, request: ChatRequest, context: OperationContext): ChatReply {
        progressBus.emit(request.correlationId, ChatProgressEvent.StepStarted("Answer"))
        val start = System.currentTimeMillis()
        val toolContext = if (results.executions.isEmpty()) {
            "No tools were needed for this message."
        } else {
            results.executions.joinToString("\n\n") {
                "Tool ${it.tool} (query: \"${it.query}\"):\n${it.rawOutput}"
            }
        }
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

                Respond with raw JSON only. Do not wrap it in markdown code
                fences or backticks, and do not add any other text.
                """.trimIndent(),
                AnswerText::class.java,
            )
        val seconds = (System.currentTimeMillis() - start) / 1000.0
        progressBus.emit(request.correlationId, ChatProgressEvent.StepFinished("Answer", seconds))
        val toolCalls = results.executions.map {
            ToolCallSummary(
                tool = it.tool,
                query = it.query,
                failed = isToolError(it.rawOutput),
                seconds = it.durationMs / 1000.0,
            )
        }
        // Steps accumulated from planTools2 -> executeTools2, plus this
        // step's own time - the full pipeline timeline for the UI.
        val steps = results.timings + StepTiming("Answer", seconds)
        val reply = ChatReply(answered.text, toolCalls, steps)
        // Terminal event for the live stream - ChatController's /chat/stream
        // endpoint could emit this itself once AgentInvocation.invoke(...)
        // returns the same reply, but emitting it here means answer, not
        // the controller, stays the one place that decides the turn is done.
        progressBus.emit(request.correlationId, ChatProgressEvent.Done(reply))
        return reply
    }
}

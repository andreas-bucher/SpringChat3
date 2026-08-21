package ch.arcticsoft.springchat3.agent

/**
 * Incoming chat turn from the WebFlux layer.
 *
 * [latitude]/[longitude] come from the browser's Geolocation API (see
 * static/index.html) - null if the user's browser doesn't support it,
 * hasn't granted permission, or the frontend didn't bother asking (e.g. a
 * plain curl request). Unused by ChatAgent2 now that both the LOCATION and
 * WEATHER/WEATHER_FORECAST tools have been removed - nothing currently
 * reads them, but they're left here rather than deleted in case a future
 * tool needs the browser's location again. index.html still asks for and
 * sends them.
 *
 * [correlationId] is set by ChatController's `/chat/stream` endpoint (a
 * fresh random ID per request) so ChatAgent2 can attribute the live
 * [ChatProgressEvent]s it emits while working (see ChatProgress.kt) to the
 * right in-flight browser connection via [ChatProgressBus]. It's blank for
 * the plain `/chat` endpoint (and for any caller that doesn't set it, e.g. a
 * raw curl request) - [ChatProgressBus.emit] is a harmless no-op for a
 * correlation ID nothing ever [ChatProgressBus.open]ed.
 */
data class ChatRequest(
    val message: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val correlationId: String = "",
)

/** The tools [ChatAgent2.planTools2] can choose from. */
enum class ToolName {
    PUBLIC_TRANSPORT,
}

/** One tool the LLM decided is relevant, with the input it should be called with. */
data class PlannedToolCall(
    val tool: ToolName,
    val query: String,
    val reason: String,
)

/**
 * Zero or more tool calls the LLM decided would help answer the message.
 *
 * [timings] is never populated by the LLM (it's not mentioned in the
 * planTools2 prompt) - Jackson just leaves it at its default empty list when
 * parsing the LLM's JSON, and planTools2 attaches its own [StepTiming]
 * afterwards via `.copy(...)`. It exists here, on the plan itself, purely so
 * later steps (executeTools2, answer) - which receive this object from
 * Embabel's blackboard - can carry it forward and append their own; see
 * ChatAgent2 for how it accumulates.
 */
data class ToolPlan(val calls: List<PlannedToolCall>, val timings: List<StepTiming> = emptyList())

/** Raw output of one executed tool call, plus how long it took to run. */
data class ToolExecution(
    val tool: ToolName,
    val query: String,
    val rawOutput: String,
    val durationMs: Long,
)

/** All tool executions for this turn (empty if none were needed). */
data class ToolResults(val executions: List<ToolExecution>, val timings: List<StepTiming> = emptyList())

/**
 * One tool call surfaced to the UI alongside the reply, so the chat frontend
 * can show what the agent actually did this turn (e.g. a small "used
 * PUBLIC_TRANSPORT: Zürich HB" chip) instead of the tool use being invisible.
 *
 * [failed] is a best-effort heuristic, not a hard guarantee: executeTools2
 * (see ChatAgent2.kt) always returns a JSON string, so this just checks
 * whether that string looks like one of the `{"error": ...}` shapes the
 * tools/executeTools2 themselves use for both thrown exceptions and
 * graceful not-found/no-result cases - it can't distinguish "tool threw"
 * from "tool legitimately reported a problem", but either way that's the
 * right moment to flag it in the UI.
 *
 * [seconds] is how long executeTools2 spent on this call, wall-clock, so the
 * UI can show e.g. "Public transport 0.4s".
 */
data class ToolCallSummary(
    val tool: ToolName,
    val query: String,
    val failed: Boolean,
    val seconds: Double,
)

/**
 * Wall-clock time spent in one pipeline step (an @Action method), for the
 * UI's step-by-step timeline - the agent-level equivalent of
 * [ToolCallSummary.seconds] for individual tool calls. [step] is a short,
 * human-readable label (e.g. "Plan tools"), not the Kotlin method/action name.
 */
data class StepTiming(val step: String, val seconds: Double)

/**
 * Final reply returned to the caller. [toolCalls] and [steps] are both
 * populated in code, never by an LLM.
 */
data class ChatReply(
    val text: String,
    val toolCalls: List<ToolCallSummary> = emptyList(),
    val steps: List<StepTiming> = emptyList(),
)

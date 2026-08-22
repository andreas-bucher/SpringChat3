package ch.arcticsoft.springchat3.agent

/**
 * Incoming chat turn from the WebFlux layer.
 *
 * [latitude]/[longitude] come from the browser's Geolocation API (see
 * static/index.html) - null if the user's browser doesn't support it,
 * hasn't granted permission, or the frontend didn't bother asking (e.g. a
 * plain curl request). Read by [ChatAgent.analyzeMessage] to construct a
 * per-request [ch.arcticsoft.springchat3.tools.CurrentLocationTool] - see
 * that class's doc comment for why the coordinates are baked into the tool
 * object itself rather than something the LLM supplies as an argument.
 *
 * [correlationId] is set by ChatController's `/chat/stream` endpoint (a
 * fresh random ID per request) so [ChatAgent] can attribute the live
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

/**
 * [ChatAgent.analyzeMessage]'s own createObject target - the small planning
 * model's one-line summary of what it did, if anything. Deliberately
 * lightweight and mostly discarded: the actual tool-call results
 * [ChatAgent.answer] works from come from [ToolCallProgressBridge]'s capture
 * of the real tool calls (see that class's doc comment), not from this
 * model-written note - the note only exists because `createObject` needs
 * *some* target type, and a short "what did you just do" summary is a cheap
 * way to keep the small model's own output legible in logs if you go
 * looking, without asking it to re-digest or summarize raw tool output the
 * way a dedicated summarization pass would (this app deliberately has none -
 * see [ChatAgent]'s class doc comment).
 */
data class ToolGatheringNote(val note: String = "")

/**
 * Raw output of one tool call the LLM made natively via
 * `PromptRunner.withToolObject(...)` (Spring AI/Embabel's real function
 * calling - see [ch.arcticsoft.springchat3.tools.GeoTool]'s doc comment),
 * plus how long it took to run.
 *
 * [tool] is the tool's registered name (its `@Tool(name = ...)`, e.g.
 * `"lookup_place"`) rather than an enum - there's no longer a fixed,
 * hand-maintained list of tools application code has to switch over, since
 * dispatch now happens inside Spring AI's own tool-calling machinery, not a
 * `when (call.tool)` this app writes itself.
 *
 * [input] is the tool call's raw argument payload as Embabel's
 * `ToolCallRequestEvent`/`ToolCallResponseEvent` report it (attested, not
 * compile-verified, to be the JSON-encoded arguments object, e.g.
 * `{"place":"Interlaken"}` - see [ToolCallProgressBridge]'s doc comment) -
 * not a single free-text "query" string the way this app's previous
 * hand-rolled dispatch worked.
 *
 * Captured by [ToolCallProgressBridge] (an `AgenticEventListener`) rather
 * than built inline in a loop [ChatAgent] itself controls, since the actual
 * tool dispatch now happens inside `createObject(...)`, not in application
 * code between two steps of a manual plan/execute split.
 */
data class ToolExecution(
    val tool: String,
    val input: String,
    val rawOutput: String,
    val durationMs: Long,
)

/** All tool executions [ChatAgent.analyzeMessage] made this turn (empty if none were needed). */
data class ToolResults(val executions: List<ToolExecution>, val timings: List<StepTiming> = emptyList())

/**
 * One tool call surfaced to the UI alongside the reply, so the chat frontend
 * can show what the agent actually did this turn (e.g. a small "Lookup
 * place: Interlaken" chip) instead of the tool use being invisible.
 *
 * [failed] is a best-effort heuristic, not a hard guarantee - see
 * [ToolCallProgressBridge] for how it's derived from the tool call's
 * `Result<String>`.
 *
 * [seconds] is wall-clock time for this one call, so the UI can show e.g.
 * "Lookup place 0.4s".
 */
data class ToolCallSummary(
    val tool: String,
    val input: String,
    val failed: Boolean,
    val seconds: Double,
)

/**
 * Wall-clock time spent in one pipeline step (an @Action method), for the
 * UI's step-by-step timeline - the agent-level equivalent of
 * [ToolCallSummary.seconds] for individual tool calls. [step] is a short,
 * human-readable label (e.g. "Analyzing message ..."), not the Kotlin
 * method/action name.
 */
data class StepTiming(val step: String, val seconds: Double)

/** Just the reply text - what the single answering LLM call is actually asked to produce. */
data class AnswerText(val text: String)

/**
 * Final reply returned to the caller. [toolCalls] and [steps] are both
 * populated in code, never by an LLM.
 */
data class ChatReply(
    val text: String,
    val toolCalls: List<ToolCallSummary> = emptyList(),
    val steps: List<StepTiming> = emptyList(),
)

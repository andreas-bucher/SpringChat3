package ch.arcticsoft.springchat3.agent

import com.embabel.agent.api.event.AgentPlatformEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.api.event.ToolCallResponseEvent
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridges Embabel's own tool-call events (fired for every native tool call
 * `PromptRunner.withToolObject(...)`-registered tools go through - see
 * [ch.arcticsoft.springchat3.tools.GeoTool]'s doc comment) to
 * [ChatProgressBus]'s live per-tool-call UI updates, and separately collects
 * each call's raw output into a plain list [ChatAgent.analyzeMessage] returns as
 * part of [ToolResults].
 *
 * Why this exists at all: with native tool-calling, the actual dispatch
 * (which tool, with which arguments, calling which HTTP endpoint) happens
 * inside Spring AI/Embabel's own machinery during a single
 * `context.ai()....createObject(...)` call - there's no `when (call.tool)`
 * loop in [ChatAgent] itself any more for this class to hook into directly.
 * [ToolCallRequestEvent]/[ToolCallResponseEvent] (part of Embabel's
 * `AgenticEventListener` mechanism, confirmed against embabel-agent's actual
 * source rather than guessed) are the only remaining place to observe them.
 *
 * CORRELATION (rewritten 2026-08-21 - see [[springchat3_native_tool_calling]]
 * in project memory for the full incident this replaced): correlation is
 * keyed by Embabel's own `AgentProcess.id`, exposed as `processId` on both
 * [ToolCallRequestEvent]/[ToolCallResponseEvent] (they extend Embabel's
 * `AbstractAgentProcessEvent`, which derives `processId` from
 * `agentProcess.id`) and, on the calling side, via
 * `OperationContext.agentProcess.id` - [ChatAgent.analyzeMessage] passes
 * that id into [withCapture] so it can be registered in [activeCaptures]
 * BEFORE the tool-calling `createObject(...)` call starts, and looked up
 * from whichever thread later fires the response event. Confirmed both
 * against embabel-agent's actual Kotlin source (`AgentProcessEvent.kt`,
 * `OperationContext.kt` on GitHub) AND against a real request/response round
 * trip (`processId` "epic_bouman" matched on both sides, `get_user_location`
 * output reached [ChatAgent.answer] correctly) - **confirmed working, not
 * just attested**. One earlier test of this exact same code appeared to
 * still fail, with no fix in between - the cause of that was never pinned
 * down (a stale build before rebuilding? the local model narrating "using
 * get_user_location..." without actually invoking it that one time, which
 * would produce the identical symptom with nothing wrong in this class at
 * all?) - see [[springchat3_native_tool_calling]] for that detour. Worth
 * remembering if the symptom ever reappears: don't assume this mechanism
 * broke again before checking whether the tool was actually called.
 *
 * This replaces an earlier `ThreadLocal`-based version that assumed
 * Embabel's `AgenticEventListener` callbacks fire synchronously on the same
 * thread that called `createObject(...)`. That assumption turned out to be
 * false in practice: a real request logged a correct tool result in the
 * model's own [ch.arcticsoft.springchat3.agent.ToolGatheringNote] (proving
 * the tool call itself succeeded) while [ToolResults.executions] came back
 * empty (proving this class never recorded it) - `ThreadLocal` values don't
 * cross threads, so `activeExecutions.get()`/`activeCorrelationId.get()`
 * silently returned `null` on whatever thread Embabel actually dispatched
 * the tool call on, and the response was dropped with no error. A
 * `ConcurrentHashMap` keyed by the stable per-request `processId` has no
 * such thread-affinity requirement.
 */
@Component
class ToolCallProgressBridge(private val progressBus: ChatProgressBus) : AgenticEventListener {

    private val log = LoggerFactory.getLogger(ToolCallProgressBridge::class.java)

    private class ActiveCapture(val correlationId: String) {
        val executions = CopyOnWriteArrayList<ToolExecution>()
        val index = AtomicInteger(0)
    }

    private val activeCaptures = ConcurrentHashMap<String, ActiveCapture>()

    // Request-event identity -> when it started and which per-request index
    // it was assigned, closed out when the matching response event arrives -
    // keyed by the event object itself rather than trying to re-derive the
    // index from a shared counter's current value, so this stays correct
    // even if a future Embabel version ever runs several tool calls
    // concurrently rather than strictly one-at-a-time.
    private data class PendingCall(val startedAtMs: Long, val index: Int)
    private val pending = ConcurrentHashMap<ToolCallRequestEvent, PendingCall>()

    /**
     * Runs [block] (expected to be [ChatAgent.analyzeMessage]'s single
     * `createObject(...)` call) with tool-call capture active for
     * [processId] (Embabel's `AgentProcess.id` for this request - see this
     * class's doc comment for why that, not a `ThreadLocal`, is the
     * correlation key) / [correlationId] (this app's own id, used only for
     * [ChatProgressBus] events), returning both [block]'s own result and
     * every [ToolExecution] observed while it ran.
     *
     * [block] returns `null` (rather than propagating) if it throws - added
     * 2026-08-21 after a real request showed the failure mode this guards
     * against: both tool calls it made (`lookup_place` then
     * `get_meteoswiss_weather`) succeeded and were captured here exactly as
     * designed, but the *separate*, purely-cosmetic step where the model
     * writes its own `ToolGatheringNote` afterwards came back as plain text
     * ("nothing needed") instead of the required `{"note": "..."}` JSON,
     * which `FilteringJacksonOutputConverter`/`ExceptionWrappingConverter`
     * couldn't repair and ultimately threw from inside `block()`. Before this
     * fix, that exception would propagate straight out of `withCapture`,
     * discarding the [ActiveCapture.executions] already collected below (the
     * `finally` still runs, but the caller never sees them) and failing the
     * whole turn - even though every tool had already returned real, usable
     * data. [ChatAgent.answer] never reads the note (see
     * [ToolGatheringNote]'s doc comment), so losing it costs nothing;
     * losing the executions would have thrown away real weather/geocoding
     * results over a formatting slip in an unrelated, discardable field.
     */
    fun <T> withCapture(processId: String, correlationId: String, block: () -> T): Pair<T?, List<ToolExecution>> {
        log.debug("withCapture processid: ${processId} correlationId: ${correlationId}")
        val capture = ActiveCapture(correlationId)
        activeCaptures[processId] = capture
        return try {
            val result = block()
            result to capture.executions.toList()
        } catch (e: Exception) {
            log.warn(
                "analyzeMessage's final createObject(...) call failed after {} tool call(s) already " +
                    "executed - proceeding with the captured tool results and treating the note as " +
                    "unavailable, rather than discarding real tool output over this",
                capture.executions.size,
                e,
            )
            null to capture.executions.toList()
        } finally {
            activeCaptures.remove(processId)
        }
    }

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is ToolCallRequestEvent -> {
                val capture = activeCaptures[event.processId]
                val index = capture?.index?.getAndIncrement() ?: 0
                pending[event] = PendingCall(System.currentTimeMillis(), index)
                log.debug(
                    "Tool call requested: {} (input: {}, processId: {})",
                    event.tool,
                    event.toolInput,
                    event.processId,
                )
                val correlationId = capture?.correlationId ?: return
                progressBus.emit(correlationId, ChatProgressEvent.ToolStarted(index, event.tool.toString(), event.toolInput))
            }

            is ToolCallResponseEvent -> {
                val call = pending.remove(event.request) ?: PendingCall(System.currentTimeMillis(), 0)
                val durationMs = System.currentTimeMillis() - call.startedAtMs
                val outputText = event.result.getOrElse { e ->
                    """{"error": "${event.request.tool} failed: ${e.message}"}"""
                }
                val failed = event.result.isFailure || outputText.trimStart().startsWith("{\"error\"")

                val capture = activeCaptures[event.processId]
                capture?.executions?.add(
                    ToolExecution(event.request.tool.toString(), event.request.toolInput, outputText, durationMs),
                )
                val correlationId = capture?.correlationId ?: return
                progressBus.emit(
                    correlationId,
                    ChatProgressEvent.ToolFinished(
                        call.index,
                        event.request.tool.toString(),
                        event.request.toolInput,
                        durationMs / 1000.0,
                        failed,
                    ),
                )
            }

            else -> {
                // Everything else (process lifecycle, planning, ranking, ...) is irrelevant here.
            }
        }
    }

    override fun onPlatformEvent(event: AgentPlatformEvent) {
        // No platform-level events (ranking choices, agent deployment) are relevant to per-tool-call progress.
    }
}

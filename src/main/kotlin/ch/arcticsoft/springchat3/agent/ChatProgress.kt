package ch.arcticsoft.springchat3.agent

import org.springframework.stereotype.Component
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

/**
 * One live progress update pushed to the browser while [ChatAgent2] works
 * through a turn, over the NDJSON stream `ChatController`'s `/chat/stream`
 * returns - see index.html's live-trace rendering for how each variant
 * updates the UI.
 *
 * [type] is written out explicitly as a plain string property (rather than
 * relying on Jackson's `@JsonTypeInfo` polymorphic-type machinery, which
 * wraps/renames things) so the JSON on the wire is just `{"type": "...",
 * ...}` - obvious to read and trivial for the frontend to switch on. Jackson
 * serializes each event by its actual runtime subtype (its default
 * behavior for a non-final base type), so [step]/[seconds]/etc. show up
 * alongside [type] with no extra configuration needed - this is a
 * write-only model (server -> browser), never deserialized back into Kotlin,
 * so there's no need to teach Jackson how to read [type] back into a
 * subtype either.
 *
 * [ToolStarted]/[ToolFinished] carry [index] - [PlannedToolCall]'s position
 * in the plan - rather than relying on arrival order to pair a tool's start
 * with its finish; executeTools2 runs calls sequentially today so order
 * alone would work, but an index makes the frontend's pairing correct even
 * if that ever changes to run calls concurrently.
 */
sealed class ChatProgressEvent(val type: String) {
    data class StepStarted(val step: String) : ChatProgressEvent("step-started")
    data class StepFinished(val step: String, val seconds: Double) : ChatProgressEvent("step-finished")
    data class ToolStarted(val index: Int, val tool: ToolName, val query: String) :
        ChatProgressEvent("tool-started")
    data class ToolFinished(
        val index: Int,
        val tool: ToolName,
        val query: String,
        val seconds: Double,
        val failed: Boolean,
    ) : ChatProgressEvent("tool-finished")

    /** Terminal event: the turn is fully answered - [reply] is the same object `/chat` (non-streaming) returns. */
    data class Done(val reply: ChatReply) : ChatProgressEvent("done")

    /** Terminal event: the agent invocation itself threw - contrast with a single failed tool call, which is not fatal. */
    data class Failed(val message: String) : ChatProgressEvent("failed")
}

/**
 * Fan-in point between [ChatAgent2] (emits events as it works, correlated by
 * [ChatRequest.correlationId]) and `ChatController`'s `/chat/stream`
 * endpoint (opens one sink per request, returns it as the streamed response
 * body, closes it once that request is done).
 *
 * One [Sinks.Many] per in-flight request, held only as long as that request
 * is in flight - `unicast()` because exactly one subscriber (that one HTTP
 * response) ever reads a given sink, never shared/replayed to anyone else.
 */
@Component
class ChatProgressBus {
    private val sinks = ConcurrentHashMap<String, Sinks.Many<ChatProgressEvent>>()

    fun open(correlationId: String): Sinks.Many<ChatProgressEvent> {
        val sink = Sinks.many().unicast().onBackpressureBuffer<ChatProgressEvent>()
        sinks[correlationId] = sink
        return sink
    }

    /** No-op if [correlationId] is blank or was never [open]ed (e.g. the plain, non-streaming `/chat` endpoint). */
    fun emit(correlationId: String, event: ChatProgressEvent) {
        sinks[correlationId]?.tryEmitNext(event)
    }

    fun close(correlationId: String) {
        sinks.remove(correlationId)
    }
}

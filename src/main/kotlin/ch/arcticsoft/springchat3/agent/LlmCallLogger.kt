package ch.arcticsoft.springchat3.agent

import com.embabel.agent.api.event.AgentPlatformEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.LlmResponseEvent
import com.embabel.agent.api.event.ToolLoopCompletedEvent
import com.embabel.agent.spi.support.springai.ChatModelCallEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One log line per LLM call, and a count per turn (2026-08-29, user's own
 * request: "can you make a log output for every LLM call made. I would like
 * to better understand how many LLM calls are made").
 *
 * **The count is not the number of steps, and that is the whole point.** The
 * UI trace shows four actions at most, but an action that calls tools is a
 * LOOP: the model is called again after every tool result, so one "Editing
 * document ... 24s" row can be one call or seven. Nothing in this app could
 * tell those apart before this class - which is exactly the question being
 * asked, and the same question that matters for how long a turn takes and
 * how hard the local Ollama is being worked.
 *
 * Registered the way [ToolCallProgressBridge] already is: a `@Component`
 * implementing Embabel's [AgenticEventListener], which the platform collects
 * as a bean. That bridge is the precedent for everything here - see its doc
 * comment for how these events correlate by `AgentProcess.id`.
 *
 * Every event type and accessor below was verified against the bundled
 * embabel 1.0.0 jars (class-file method tables read in Python) rather than
 * assumed: `LlmRequestEvent<O>`/`LlmResponseEvent<O>`/`ChatModelCallEvent<O>`
 * are generic and all three extend `AbstractAgentProcessEvent`, so they carry
 * `processId` and arrive through [onProcessEvent];
 * `LlmResponseEvent.runningTime` is a `Duration`;
 * `ToolLoopCompletedEvent.totalIterations`/`maxIterations`/`toolNames` exist
 * and it is NOT generic; and `Action` reaches `getName()` through
 * `DataDictionary : Named`.
 *
 * **[ChatModelCallEvent] is counted SEPARATELY on purpose.** It is a sibling
 * of [LlmRequestEvent], not a subclass, and it lives in Embabel's Spring AI
 * support package - so it is probably the lower-level "a chat model was
 * actually called" event while [LlmRequestEvent] is the higher-level "this
 * action asked for a completion". Which of them fires per HTTP round trip
 * cannot be settled from the bytecode, and guessing would defeat the purpose
 * of a measurement. Both are logged and both are totalled, labelled; one run
 * of a tool-calling turn will show which is which, and the loser can be
 * dropped then.
 *
 * At INFO because it was asked for and is meant to be read. To quiet it
 * without touching code:
 * `logging.level.ch.arcticsoft.springchat3.agent.LlmCallLogger=WARN`.
 */
@Component
class LlmCallLogger : AgenticEventListener {

    private val log = LoggerFactory.getLogger(LlmCallLogger::class.java)

    companion object {
        /**
         * `Action` is NULLABLE on all three of these events (`Action?`), not
         * merely nullable-in-theory: an LLM call can be made outside any
         * action - Embabel's own planning and ranking calls are the obvious
         * case - and those events carry no action at all.
         *
         * Learned from the compiler on 2026-08-29, not from the jar: a class
         * file's method descriptor says `()Lcom/embabel/agent/core/Action;`
         * with no hint of nullability, because that lives in Kotlin metadata
         * and JSR-305 annotations rather than in the descriptor. Reading
         * method tables out of a jar verifies that a member EXISTS and what
         * it returns - never whether it can be null. See build_verification.md.
         */
        private const val NO_ACTION = "(no action)"
    }

    private class Turn {
        val startedAtMs = System.currentTimeMillis()
        val llmRequests = AtomicInteger()
        val chatModelCalls = AtomicInteger()
        val toolLoops = AtomicInteger()
        val toolLoopIterations = AtomicInteger()
    }

    /**
     * Keyed by Embabel's `AgentProcess.id` - one entry per turn, removed when
     * that process finishes. A turn that never emits a finished event (a
     * crash, a kill) leaks one small entry; bounded by how many turns fail,
     * and cheaper than a background sweep for a single-user app.
     */
    private val turns = ConcurrentHashMap<String, Turn>()

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is LlmRequestEvent<*> -> {
                val turn = turns.computeIfAbsent(event.processId) { Turn() }
                log.info(
                    "LLM call #{} [{}] model={} action={} output={} messages={}",
                    turn.llmRequests.incrementAndGet(),
                    event.processId,
                    event.interaction.llm.model,
                    event.action?.name ?: NO_ACTION,
                    event.outputClass.simpleName,
                    event.messages.size,
                )
            }

            is LlmResponseEvent<*> -> {
                log.info(
                    "LLM call done [{}] model={} action={} in {}s",
                    event.processId,
                    event.request.interaction.llm.model,
                    event.request.action?.name ?: NO_ACTION,
                    event.runningTime.toMillis() / 1000.0,
                )
            }

            // Counted apart from the above - see this class's doc comment for
            // why both are measured rather than one of them assumed.
            is ChatModelCallEvent<*> -> {
                val turn = turns.computeIfAbsent(event.processId) { Turn() }
                log.info(
                    "Chat model call #{} [{}] model={} output={}",
                    turn.chatModelCalls.incrementAndGet(),
                    event.processId,
                    event.interaction.llm.model,
                    event.outputClass.simpleName,
                )
            }

            /*
             * The definitive answer to "how many times was the model called
             * for one tool-using step": totalIterations. Worth more than any
             * count this class keeps itself, because it comes from the loop
             * that actually ran. maxIterations is logged beside it so a step
             * that stopped because it hit the ceiling - rather than because
             * it was finished - is visible rather than silent.
             */
            is ToolLoopCompletedEvent -> {
                val turn = turns.computeIfAbsent(event.processId) { Turn() }
                turn.toolLoops.incrementAndGet()
                turn.toolLoopIterations.addAndGet(event.totalIterations)
                log.info(
                    "Tool loop finished [{}] action={} {} of max {} iteration(s) in {}s, tools offered: {}{}",
                    event.processId,
                    event.action?.name ?: NO_ACTION,
                    event.totalIterations,
                    event.maxIterations,
                    event.runningTime.toMillis() / 1000.0,
                    event.toolNames.size,
                    if (event.totalIterations >= event.maxIterations) " - STOPPED AT THE LIMIT" else "",
                )
            }

            is AgentProcessFinishedEvent -> {
                val turn = turns.remove(event.processId) ?: return
                log.info(
                    "Turn finished [{}] in {}s: {} LLM request(s), {} chat model call(s), " +
                        "{} tool loop(s) totalling {} iteration(s)",
                    event.processId,
                    (System.currentTimeMillis() - turn.startedAtMs) / 1000.0,
                    turn.llmRequests.get(),
                    turn.chatModelCalls.get(),
                    turn.toolLoops.get(),
                    turn.toolLoopIterations.get(),
                )
            }

            else -> {
                // Planning, ranking, embedding and process lifecycle events
                // are not LLM calls to this app's chat models - deliberately
                // not counted, so the number stays the one that was asked for.
            }
        }
    }

    override fun onPlatformEvent(event: AgentPlatformEvent) {
        // Platform-level events belong to no single turn, so there is nothing
        // here to attribute a count to.
    }
}

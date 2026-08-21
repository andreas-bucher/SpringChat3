package ch.arcticsoft.springchat3.config

import com.embabel.agent.api.event.AgentPlatformEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.LlmResponseEvent
import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.api.event.ToolCallResponseEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * A second, narrower AgenticEventListener alongside Embabel's own default
 * one (LoggingAgenticEventListener, registered unconditionally by
 * AgentPlatformConfiguration and logging everything through one logger
 * named "Embabel" - see the `logging.level` comment in application.yml).
 * Both listeners receive every event; AgentPlatformConfiguration's
 * eventListener() bean multicasts to all registered AgenticEventListener
 * beans, so this one doesn't replace or interfere with the default.
 *
 * The default listener's problem for tuning log output isn't that it's too
 * verbose - it's that it's all-or-nothing. It logs ~15 different event
 * types (process creation, ranking choices, plan formulation, state
 * transitions, tool calls, LLM request/response, goal achieved, process
 * completion, ...) through that single "Embabel" logger, so
 * `logging.level.Embabel` can only show everything or almost nothing.
 *
 * This listener picks out the two categories most worth toggling
 * independently - LLM request/response traffic and tool calls - and logs
 * each through its own SLF4J logger name ("Embabel.Llm" / "Embabel.Tools").
 * Logback treats dot-separated logger names as a hierarchy regardless of
 * whether they're real packages, so these are children of "Embabel" for
 * level *inheritance* but take their own level the moment one is set
 * explicitly - giving independent on/off switches in application.yml:
 *
 *   logging:
 *     level:
 *       Embabel: WARN        # keep the default narration quiet
 *       Embabel.Llm: INFO    # but see every prompt/response, and which
 *                             # model (granite vs. mistral/magistral)
 *                             # handled which step
 *       Embabel.Tools: INFO  # and every GeoTool/WeatherTool/TransportTool/
 *                             # web-search call the LLM made
 *
 * To split out another category the same way (e.g. plan formulation, state
 * transitions), add a logger val below, a `when` branch in onProcessEvent
 * for the relevant event type(s) from com.embabel.agent.api.event
 * (AgentProcessEvent.kt has the full list), and a matching
 * `logging.level.Embabel.<Whatever>` entry.
 */
@Configuration
class GranularEventLoggingConfig {

    @Bean
    fun granularEventLogger(): AgenticEventListener = GranularEventLogger()
}

private class GranularEventLogger : AgenticEventListener {

    private val llmLog: Logger = LoggerFactory.getLogger("Embabel.Llm")
    private val toolLog: Logger = LoggerFactory.getLogger("Embabel.Tools")

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is LlmRequestEvent<*> -> llmLog.info(
                "[{}] {} -> {}: {}",
                event.processId,
                event.action?.name ?: "?",
                event.llmMetadata.name,
                event.messages.lastOrNull()?.content?.truncate(200) ?: "(no messages)",
            )

            is LlmResponseEvent<*> -> llmLog.info(
                "[{}] {} <- {} ({}ms): {}",
                event.request.processId,
                event.request.action?.name ?: "?",
                event.request.llmMetadata.name,
                event.runningTime.toMillis(),
                event.response.toString().truncate(200),
            )

            is ToolCallRequestEvent -> toolLog.info(
                "[{}] {} -> {}({})",
                event.processId,
                event.action?.name ?: "?",
                event.tool,
                event.toolInput.truncate(200),
            )

            is ToolCallResponseEvent -> toolLog.info(
                "[{}] {} <- {} ({}ms): {}",
                event.request.processId,
                event.request.action?.name ?: "?",
                event.request.tool,
                event.runningTime.toMillis(),
                event.result.fold(
                    onSuccess = { it.truncate(200) },
                    onFailure = { "FAILED: ${it.message}" },
                ),
            )

            else -> {
                // Everything else (process lifecycle, planning, ranking, ...)
                // stays exclusively on the default "Embabel" narration logger.
            }
        }
    }

    override fun onPlatformEvent(event: AgentPlatformEvent) {
        // Platform-level events (ranking choices, agent deployment, dynamic
        // agent creation) aren't split out here - add a `when` the same way
        // as onProcessEvent above if one of them needs its own switch too.
    }

    private fun String.truncate(maxLength: Int): String =
        if (length <= maxLength) this else take(maxLength) + "…"
}

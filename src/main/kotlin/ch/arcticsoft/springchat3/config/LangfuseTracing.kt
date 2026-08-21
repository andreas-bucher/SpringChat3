package ch.arcticsoft.springchat3.config

import ch.arcticsoft.springchat3.agent.ChatReply
import ch.arcticsoft.springchat3.agent.ChatRequest
import com.embabel.agent.api.event.ActionExecutionResultEvent
import com.embabel.agent.api.event.ActionExecutionStartEvent
import com.embabel.agent.api.event.AgentPlatformEvent
import com.embabel.agent.api.event.AgentProcessCreationEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.LlmResponseEvent
import com.embabel.agent.api.event.ObjectAddedEvent
import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.api.event.ToolCallResponseEvent
import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports every Embabel action / LLM call / tool call as an OpenTelemetry
 * span, exported via OTLP straight to Langfuse (https://langfuse.com) - one
 * chat turn becomes one Langfuse trace: classifyIntent -> planTools ->
 * executeTools (with each tool call nested inside it) -> draftReply ->
 * reviewReply, with the two LLM-calling steps rendered as "generation"
 * observations (model name, input, output), following Langfuse's OTel
 * attribute conventions - see https://langfuse.com/integrations/native/opentelemetry.
 *
 * Reviewed against Langfuse's own instrumentation checklist (from its
 * langfuse/skills Claude-agent skill) on 2026-08-20; see the per-item notes
 * below and springchat3_langfuse.md in project memory for the full audit.
 *
 * WHY THIS HOOKS EMBABEL'S OWN EVENTS RATHER THAN GENERIC SPRING/MICROMETER
 * TRACING: the "official" low-code path (add micrometer-tracing-bridge-otel
 * + point management.otlp.tracing.* at Langfuse) relies on Spring Boot's
 * auto-instrumented RestClient/ChatModel machinery. But per HttpClientConfig's
 * own doc comment, Embabel's Ollama model calls go through a specially
 * @Qualifier-ed RestClient.Builder that bypasses that auto-instrumentation
 * entirely - so the generic path would likely produce, at best, a trace for
 * the inbound /chat HTTP request with no LLM/tool detail inside it. Hooking
 * AgenticEventListener instead (the same mechanism GranularEventLoggingConfig
 * already uses for the Embabel.Llm/Embabel.Tools loggers) guarantees real
 * prompt/response/timing data regardless of what's happening in the HTTP
 * layer underneath, since it's Embabel's own instrumentation, not derived
 * instrumentation of some HTTP client it happens to use internally. There is
 * also no framework-level Langfuse<->Embabel integration to fall back on:
 * Langfuse's own skill/docs have no Java/Kotlin/Spring instrumentation
 * guidance at all (JS/Python only), and the one community project claiming
 * to bridge the two (quantpulsar/opentelemetry-exporter-langfuse) gave
 * inconsistent artifact coordinates across two fetches and had no adoption
 * signal - not something to depend on sight unseen, so this hand-builds the
 * mapping against Langfuse's documented OTel span attributes instead.
 *
 * WHY OTLP AND NOT LANGFUSE'S OWN INGESTION API: Langfuse has a simpler,
 * dedicated REST ingestion API (POST /api/public/ingestion with
 * trace-create/generation-create events), but it's deprecated and sunsets
 * on Langfuse Cloud on 2026-11-16 in favor of the OTLP traces endpoint
 * (/api/public/otel/v1/traces) - so this targets that instead, using the
 * OpenTelemetry Java SDK directly (not Spring's Micrometer bridge, to avoid
 * any interference with/dependence on Spring Boot's own tracing
 * autoconfiguration, and so this works whether or not that's ever added).
 *
 * TRACE-LEVEL INPUT/OUTPUT: Langfuse's dedicated `langfuse.trace.input`/
 * `langfuse.trace.output` span attributes are documented as deprecated as of
 * Langfuse v4, "retained for legacy trace-level evaluators" - the current
 * approach is that the trace's root span's own `langfuse.observation.input`/
 * `output` automatically becomes the trace's input/output in the UI. That's
 * what this does: the root "chat" span gets the user's message as its input
 * (as soon as the ChatRequest object is bound to the blackboard) and the
 * final ChatReply.text as its output (once reviewReply produces it), via
 * ObjectAddedEvent - the same event Embabel fires for every @Action's return
 * value, filtered down to just those two types here.
 *
 * DYNAMIC TRACE NAMING: the root span starts out named "chat" (in case
 * nothing ever binds a ChatRequest - defensive only) and is renamed via
 * Span.updateName(...) to a short prefix of the user's actual message once
 * that event arrives, so traces are distinguishable in the Langfuse UI
 * instead of every single one showing up as just "chat".
 *
 * WHY processSpan() (BELOW) IS computeIfAbsent, NOT A PLAIN MAP LOOKUP: this
 * app's own source doesn't show the exact firing order of
 * AgentProcessCreationEvent vs. the ObjectAddedEvent for the initial
 * ChatRequest (both are plausible - Embabel's public GitHub source for the
 * concrete AgentProcess/blackboard-binding implementation wasn't reachable
 * to confirm it), so rather than assume an order and risk silently losing
 * the trace-input attribute if it's wrong, whichever of those two events
 * arrives first creates the root span and the other just adds its
 * attributes to the same instance. ObjectAddedEvent(ChatReply), by
 * contrast, does NOT use this - the process is always still active when the
 * final reply is produced, and a plain map lookup that no-ops if the span
 * is somehow already gone is safer than accidentally reopening a new,
 * never-closed span after the process already finished.
 *
 * KNOWN GAP - NO TOKEN USAGE: Langfuse's OTel mapping supports token
 * counts/cost via `gen_ai.usage.*` / `llm.token_count.*` /
 * `langfuse.observation.usage_details`, but Embabel's LlmRequestEvent/
 * LlmResponseEvent (embabel-agent 1.0.0) carry no usage/cost fields at all -
 * that data simply isn't exposed by the framework at this event layer, so
 * "generation" observations here will show model name and input/output but
 * no token counts or cost in the Langfuse UI. This is an upstream Embabel
 * limitation, not something fixable from application code; revisit if a
 * future embabel-agent version adds usage data to these events.
 *
 * OBSERVATION TYPES: tool-call spans are typed "tool", not the generic
 * "span" the first version of this file used - Langfuse's instrumentation
 * checklist calls out giving each call "its most specific type (retriever
 * for a lookup, agent for a subagent, etc.) rather than a generic
 * tool/span". "tool" is confirmed as a valid Langfuse observation type name
 * (https://langfuse.com/docs/observability/features/observation-types),
 * but the exact lowercase string expected by the langfuse.observation.type
 * OTel attribute specifically (as opposed to SDK as_type="tool" params)
 * wasn't independently confirmed - worth checking against the first real
 * trace. @Action-level spans stay "span", which is the right type for a
 * generic workflow step (none of classifyIntent/planTools/executeTools/
 * summarizeToolResults/draftReply/reviewReply is an agent, retriever, etc.
 * in Langfuse's sense).
 *
 * SENSITIVE DATA: `langfuse.mask-content` (default false) is an all-or-
 * nothing toggle that replaces every input/output attribute this listener
 * sets - trace, generation, and tool-span alike - with a fixed placeholder
 * instead of the real content. Left off by default because this app's
 * actual data (weather/location/transport queries, e.g. "Zurich" or a
 * lat/lon the browser already sent to the server) isn't sensitive; flip it
 * on (or replace maskIfEnabled with real PII redaction) before pointing
 * this at anything that handles content worth masking.
 *
 * Toggle the whole thing off with `langfuse.enabled` (default true) - set
 * to false, or just don't run Langfuse, to disable without removing this
 * file; failed span exports are swallowed by OpenTelemetry's
 * BatchSpanProcessor and don't affect the app, just its logs.
 */
@Configuration
@ConditionalOnProperty(name = ["langfuse.enabled"], havingValue = "true", matchIfMissing = true)
class LangfuseTracingConfig(
    @Value("\${langfuse.otlp-endpoint:http://localhost:3000/api/public/otel/v1/traces}")
    private val otlpEndpoint: String,
    @Value("\${langfuse.public-key:}")
    private val publicKey: String,
    @Value("\${langfuse.secret-key:}")
    private val secretKey: String,
    @Value("\${langfuse.mask-content:false}")
    private val maskContent: Boolean,
) {
    private val log = LoggerFactory.getLogger(LangfuseTracingConfig::class.java)

    // destroyMethod = "close" flushes any spans still buffered in the
    // BatchSpanProcessor on graceful app shutdown, rather than dropping them.
    @Bean(destroyMethod = "close")
    fun langfuseOpenTelemetrySdk(): OpenTelemetrySdk {
        if (publicKey.isBlank() || secretKey.isBlank()) {
            log.warn(
                "langfuse.public-key/langfuse.secret-key are not set - spans will still be sent to " +
                    "{}, but Langfuse will likely reject them without Basic auth. Set " +
                    "LANGFUSE_PUBLIC_KEY/LANGFUSE_SECRET_KEY (see README) once you have project keys.",
                otlpEndpoint,
            )
        }
        val exporterBuilder = OtlpHttpSpanExporter.builder().setEndpoint(otlpEndpoint)
        if (publicKey.isNotBlank() && secretKey.isNotBlank()) {
            val token = Base64.getEncoder().encodeToString("$publicKey:$secretKey".toByteArray())
            exporterBuilder.addHeader("Authorization", "Basic $token")
        }
        val resource = Resource.getDefault()
            .merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "springchat3")))
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporterBuilder.build()).build())
            .setResource(resource)
            .build()
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
    }

    @Bean
    fun langfuseTracer(langfuseOpenTelemetrySdk: OpenTelemetrySdk): Tracer =
        langfuseOpenTelemetrySdk.getTracer("ch.arcticsoft.springchat3")

    @Bean
    fun langfuseTracingListener(langfuseTracer: Tracer, objectMapper: ObjectMapper): AgenticEventListener =
        LangfuseTracingListener(langfuseTracer, objectMapper, maskContent)
}

private class LangfuseTracingListener(
    private val tracer: Tracer,
    private val objectMapper: ObjectMapper,
    private val maskContent: Boolean,
) : AgenticEventListener {

    // One root span per agent process (= one /chat turn), keyed by processId.
    private val processSpans = ConcurrentHashMap<String, Span>()

    // One span per in-flight @Action, keyed by "processId:actionName" - the
    // parent for that action's own LLM call/tool calls, if any.
    private val actionSpans = ConcurrentHashMap<String, Span>()

    // Request-event identity -> its still-open span, closed when the
    // matching response event arrives (mirrors how GranularEventLogger
    // already correlates *ResponseEvent.request back to the *RequestEvent).
    private val llmSpans = ConcurrentHashMap<LlmRequestEvent<*>, Span>()
    private val toolSpans = ConcurrentHashMap<ToolCallRequestEvent, Span>()

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is AgentProcessCreationEvent -> {
                processSpan(event.processId)
            }

            is ObjectAddedEvent -> {
                // Fires for every @Action's return value being bound to the
                // blackboard, not just these two - cheap no-op for the rest.
                when (val value = event.value) {
                    is ChatRequest -> {
                        val span = processSpan(event.processId)
                        val name = value.message.take(80).let {
                            if (value.message.length > 80) "$it…" else it
                        }
                        span.updateName(name)
                        span.setAttribute("langfuse.trace.name", name)
                        span.setAttribute("langfuse.observation.input", masked(toJson(value.message)))
                    }

                    is ChatReply -> {
                        processSpans[event.processId]?.setAttribute(
                            "langfuse.observation.output",
                            masked(toJson(value.text)),
                        )
                    }

                    else -> { /* MessageIntent/ToolPlan/ToolResults/... - not traced as trace input/output */ }
                }
            }

            is ActionExecutionStartEvent -> {
                val parent = processSpans[event.processId] ?: return
                val span = tracer.spanBuilder(event.action.name)
                    .setParent(Context.current().with(parent))
                    .setAttribute("langfuse.observation.type", "span")
                    .startSpan()
                actionSpans[actionKey(event.processId, event.action.name)] = span
            }

            is ActionExecutionResultEvent -> {
                actionSpans.remove(actionKey(event.processId, event.action.name))?.let { span ->
                    span.setAttribute("langfuse.observation.status_message", event.actionStatus.toString())
                    span.end()
                }
            }

            is LlmRequestEvent<*> -> {
                val parent = event.action?.let { actionSpans[actionKey(event.processId, it.name)] }
                    ?: processSpans[event.processId]
                    ?: return
                val span = tracer.spanBuilder("llm:${event.llmMetadata.name}")
                    .setParent(Context.current().with(parent))
                    .setAttribute("langfuse.observation.type", "generation")
                    .setAttribute("langfuse.observation.model.name", event.llmMetadata.name)
                    .setAttribute("gen_ai.request.model", event.llmMetadata.name)
                    .setAttribute("langfuse.observation.input", masked(toJson(event.messages.lastOrNull()?.content ?: "")))
                    .startSpan()
                // No token-usage attributes are set here - see the KNOWN GAP
                // note in this file's class doc comment: Embabel's
                // LlmRequestEvent/LlmResponseEvent don't expose usage data.
                llmSpans[event] = span
            }

            is LlmResponseEvent<*> -> {
                llmSpans.remove(event.request)?.let { span ->
                    span.setAttribute("langfuse.observation.output", masked(toJson(event.response.toString())))
                    span.end()
                }
            }

            is ToolCallRequestEvent -> {
                val parent = event.action?.let { actionSpans[actionKey(event.processId, it.name)] }
                    ?: processSpans[event.processId]
                    ?: return
                val span = tracer.spanBuilder("tool:${event.tool}")
                    .setParent(Context.current().with(parent))
                    // "tool" (not the generic "span") per the observation-types
                    // checklist item - see this file's OBSERVATION TYPES note.
                    .setAttribute("langfuse.observation.type", "tool")
                    .setAttribute("langfuse.observation.input", masked(toJson(event.toolInput)))
                    .startSpan()
                toolSpans[event] = span
            }

            is ToolCallResponseEvent -> {
                toolSpans.remove(event.request)?.let { span ->
                    event.result.fold(
                        onSuccess = { result -> span.setAttribute("langfuse.observation.output", masked(toJson(result))) },
                        onFailure = { error -> span.setStatus(StatusCode.ERROR, error.message ?: "tool call failed") },
                    )
                    span.end()
                }
            }

            is AgentProcessFinishedEvent -> {
                // Defensive cleanup: end (and stop leaking) any action/LLM/tool
                // spans this process opened but whose matching *ResultEvent/
                // *ResponseEvent never arrived (e.g. the process failed
                // mid-action) - otherwise they'd sit open forever and never
                // get exported.
                actionSpans.keys.filter { it.startsWith("${event.processId}:") }.forEach { key ->
                    actionSpans.remove(key)?.end()
                }
                llmSpans.keys.filter { it.processId == event.processId }.forEach { key ->
                    llmSpans.remove(key)?.end()
                }
                toolSpans.keys.filter { it.processId == event.processId }.forEach { key ->
                    toolSpans.remove(key)?.end()
                }
                processSpans.remove(event.processId)?.end()
            }

            else -> {
                // Planning/ranking/state-transition events aren't traced
                // individually - add a branch here the same way if one of
                // them needs its own span later.
            }
        }
    }

    override fun onPlatformEvent(event: AgentPlatformEvent) {
        // Platform-level events aren't scoped to one chat turn, so they don't
        // fit this trace-per-process model - nothing to do here.
    }

    // Root span for one chat turn. Idempotent by design: whichever of
    // AgentProcessCreationEvent / ObjectAddedEvent(ChatRequest) fires first
    // creates it, the other just reuses the same instance - see this file's
    // class doc comment for why the firing order isn't assumed.
    private fun processSpan(processId: String): Span =
        processSpans.computeIfAbsent(processId) { tracer.spanBuilder("chat").startSpan() }

    private fun actionKey(processId: String, actionName: String) = "$processId:$actionName"

    // Langfuse expects langfuse.observation.input/output as JSON-encoded
    // strings, not raw text - this both quotes/escapes it correctly (prompt
    // text can contain quotes, newlines, etc.) and matches that convention.
    private fun toJson(value: String): String = runCatching { objectMapper.writeValueAsString(value) }.getOrDefault("\"\"")

    // See the SENSITIVE DATA note in this file's class doc comment.
    private fun masked(json: String): String = if (maskContent) "\"[masked]\"" else json
}

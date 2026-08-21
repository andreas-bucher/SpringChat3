package ch.arcticsoft.springchat3.agent

import ch.arcticsoft.springchat3.tools.GeoTool
import ch.arcticsoft.springchat3.tools.SwissWeatherTool
import ch.arcticsoft.springchat3.tools.TransportTool
import ch.arcticsoft.springchat3.tools.WeatherTool
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.CoreToolGroups

/**
 * Incoming chat turn from the WebFlux layer.
 *
 * [latitude]/[longitude] are optional and come from the browser's
 * Geolocation API (see static/index.html) - null if the user's browser
 * doesn't support it, hasn't granted permission, or the frontend didn't
 * bother asking (e.g. a plain curl request).
 */
data class ChatRequest(
    val message: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** What the LLM thinks the user is after. */
data class MessageIntent(
    val category: String,
    val summary: String,
)

/**
 * The tools [ChatAgent.planTools] can choose from, plus two - SWISS_WEATHER
 * and SWISS_WEATHER_FORECAST - it never picks itself: planTools only ever
 * requests WEATHER/WEATHER_FORECAST, and [ChatAgent.weatherFor] swaps in the
 * Swiss variant deterministically, from the resolved location's coordinates,
 * when appropriate. They exist as distinct values so ToolExecution/the UI
 * can still show which data source actually answered the request.
 */
enum class ToolName {
    WEB_SEARCH,
    LOCATION,
    WEATHER,
    WEATHER_FORECAST,
    SWISS_WEATHER,
    SWISS_WEATHER_FORECAST,
    PUBLIC_TRANSPORT,
}

/** One tool the LLM decided is relevant, with the input it should be called with. */
data class PlannedToolCall(
    val tool: ToolName,
    val query: String,
    val reason: String,
)

/** Zero or more tool calls the LLM decided would help answer the message. */
data class ToolPlan(val calls: List<PlannedToolCall>)

/** Raw output of one executed tool call, plus how long it took to run. */
data class ToolExecution(
    val tool: ToolName,
    val query: String,
    val rawOutput: String,
    val durationMs: Long,
)

/** All tool executions for this turn (empty if none were needed). */
data class ToolResults(val executions: List<ToolExecution>)

/** Condensed, human-readable digest of the tool results. */
data class ToolSummary(val summary: String)

/** First-pass reply before review. */
data class DraftReply(val text: String)

/**
 * One tool call surfaced to the UI alongside the reply, so the chat frontend
 * can show what the agent actually did this turn (e.g. a small "used
 * WEATHER: Zürich" chip) instead of the tool use being invisible.
 *
 * [failed] is a best-effort heuristic, not a hard guarantee: executeTools
 * (see below) always returns a JSON string, so this just checks whether that
 * string looks like one of the `{"error": ...}` shapes the tools/executeTools
 * themselves use for both thrown exceptions and graceful not-found/no-result
 * cases - it can't distinguish "tool threw" from "tool legitimately reported
 * a problem", but either way that's the right moment to flag it in the UI.
 *
 * [seconds] is how long executeTools spent on this call, wall-clock, so the
 * UI can show e.g. "Weather 0.4s".
 */
data class ToolCallSummary(
    val tool: ToolName,
    val query: String,
    val failed: Boolean,
    val seconds: Double,
)

/** Just the polished text - what the generation LLM is actually asked to produce. */
data class ReviewedText(val text: String)

/** Final reply returned to the caller. [toolCalls] is populated in code, never by an LLM. */
data class ChatReply(val text: String, val toolCalls: List<ToolCallSummary> = emptyList())

/** Wrapper so the web-search sub-call fits the same createObject(..., Class) pattern as everything else. */
data class WebSearchAnswer(val text: String)

/**
 * Multi-step agent with explicit tool use:
 *
 *   ChatRequest --[classifyIntent]--> MessageIntent
 *   MessageIntent + ChatRequest --[planTools]--> ToolPlan
 *   ToolPlan --[executeTools]--> ToolResults
 *   ToolResults + MessageIntent --[summarizeToolResults]--> ToolSummary
 *   ToolSummary + ChatRequest + MessageIntent --[draftReply]--> DraftReply
 *   DraftReply + MessageIntent + ToolResults --[reviewReply, @AchievesGoal]--> ChatReply
 *
 * As before, nothing declares this order explicitly - Embabel's GOAP planner
 * infers it from each @Action method's parameter/return types.
 *
 * planTools only decides *which* tools are relevant and what input to call
 * them with; the actual work for LOCATION / WEATHER / WEATHER_FORECAST /
 * PUBLIC_TRANSPORT happens as plain, deterministic Kotlin code in
 * executeTools (a normal HTTP call via the tool beans below - no extra LLM
 * round-trip). WEB_SEARCH is the one exception: it's Embabel's built-in
 * CoreToolGroups.WEB, which only works inside the model's own
 * function-calling loop, so that particular call still goes through
 * context.ai().withToolGroup(...) rather than a plain HTTP client.
 *
 * WEATHER and WEATHER_FORECAST get one more layer of routing inside
 * executeTools ([weatherFor]): whichever of those two planTools requests,
 * the resolved location's coordinates decide - deterministically, in code -
 * whether MeteoSwiss's own regional model ([SwissWeatherTool]) or the
 * generic global one ([WeatherTool]) actually answers it. planTools/the LLM
 * never makes that call itself.
 *
 * NOTE: CoreToolGroups' package/import and PromptRunner.withToolGroup(...)
 * are based on Embabel's public docs for the 1.0.0 line, not a verified
 * local build - fix the import if it doesn't resolve.
 *
 * Two model tiers, same split as SpringChat 2 (Granite for tool/function
 * calling, Mistral for generation):
 *  - classifyIntent / planTools / summarizeToolResults / the WEB_SEARCH
 *    sub-call all use context.ai().withDefaultLlm() - embabel.models.default-llm
 *    (granite4.1:3b by default, see application.yml), a small model good at
 *    structured JSON output and tool-call planning.
 *  - draftReply / reviewReply - the two steps that actually produce and
 *    polish the reply text the user reads - use
 *    context.ai().withLlmByRole(GENERATION_LLM_ROLE) instead, resolved via
 *    embabel.models.llms.generation (mistral:latest by default). Change
 *    OLLAMA_GENERATION_MODEL (or the yml default) to point at a different
 *    locally-pulled Ollama model; Embabel auto-registers every model
 *    `ollama list` shows by its exact tag, no other wiring needed.
 */
@Agent(description = "Answers a chat message, using tools (web search, location, weather incl. MeteoSwiss, transport) where useful")
class ChatAgent(
    private val geoTool: GeoTool,
    private val weatherTool: WeatherTool,
    private val swissWeatherTool: SwissWeatherTool,
    private val transportTool: TransportTool,
) {

    companion object {
        /**
         * Role name resolved via embabel.models.llms.generation (see
         * application.yml). Used only for draftReply/reviewReply - every
         * other @Action here uses withDefaultLlm() instead.
         */
        const val GENERATION_LLM_ROLE = "generation"
    }

    @Action
    fun classifyIntent(request: ChatRequest, context: OperationContext): MessageIntent =
        context.ai()
            .withDefaultLlm()
            .createObject(
                """
                Classify the intent of this chat message in a couple of words
                (category), and summarize in one sentence what the user wants.
                Message: "${request.message}"
                Respond with raw JSON only. Do not wrap it in markdown code
                fences or backticks, and do not add any other text.
                """.trimIndent(),
                MessageIntent::class.java,
            )

    @Action
    fun planTools(intent: MessageIntent, request: ChatRequest, context: OperationContext): ToolPlan =
        context.ai()
            .withDefaultLlm()
            .createObject(
                """
                The user's message was: "${request.message}"
                Detected intent: ${intent.category} (${intent.summary})

                Decide which of these tools, if any, would help answer it:
                - WEB_SEARCH: general web search for facts/news/anything not covered below
                - LOCATION: resolve a place name to coordinates/address
                - WEATHER: current weather conditions for a place
                - WEATHER_FORECAST: multi-day weather forecast for a place
                - PUBLIC_TRANSPORT: Swiss public transport - pass a single
                  station name for next departures, or "<from> -> <to>" for a
                  connection search between two stations

                For LOCATION, WEATHER, and WEATHER_FORECAST: if the user is
                asking about their own current location or surroundings
                ("where am I", "what's the weather here/near me", "nearby"),
                use the exact literal query "${GeoTool.CURRENT_LOCATION}" instead
                of guessing a place name - the browser's actual location
                will be used for that call. Otherwise pass the place name
                mentioned in the message.

                Only include tools that are actually needed - for a message
                like "hello" or something you already know without help,
                return an empty list. For each tool you include, give the
                exact input text it should be called with (e.g. a place
                name) and a short reason.

                Respond with raw JSON only. Do not wrap it in markdown code
                fences or backticks, and do not add any other text.
                """.trimIndent(),
                ToolPlan::class.java,
            )

    @Action
    fun executeTools(plan: ToolPlan, request: ChatRequest, context: OperationContext): ToolResults {
        val executions = plan.calls.map { call ->
            val start = System.currentTimeMillis()
            // Each branch yields (the tool that actually answered, its raw
            // output) rather than just output - weatherFor's result can
            // differ from call.tool (see its doc comment), and that's what
            // gets recorded on ToolExecution below, not what planTools asked
            // for.
            val (actualTool, output) = runCatching {
                when (call.tool) {
                    ToolName.LOCATION -> call.tool to resolveLocation(call.query, request)
                    ToolName.WEATHER -> weatherFor(call.query, request, forecast = false)
                    ToolName.WEATHER_FORECAST -> weatherFor(call.query, request, forecast = true)
                    // planTools is never told about these two (see ToolName's
                    // doc comment) and shouldn't request them directly, but
                    // if it somehow does, route them exactly like their
                    // WEATHER/WEATHER_FORECAST counterparts rather than
                    // failing - weatherFor decides the actual data source
                    // itself either way.
                    ToolName.SWISS_WEATHER -> weatherFor(call.query, request, forecast = false)
                    ToolName.SWISS_WEATHER_FORECAST -> weatherFor(call.query, request, forecast = true)
                    ToolName.PUBLIC_TRANSPORT -> call.tool to transportTool.query(call.query)
                    ToolName.WEB_SEARCH -> call.tool to searchWeb(call.query, context)
                }
            }.getOrElse { e -> call.tool to """{"error": "${call.tool} failed: ${e.message}"}""" }
            val durationMs = System.currentTimeMillis() - start
            ToolExecution(actualTool, call.query, output, durationMs)
        }
        return ToolResults(executions)
    }

    /**
     * Resolves [query] to coordinates once (via [GeoTool.resolveCoordinates])
     * and deterministically routes to [SwissWeatherTool] when they fall
     * within Switzerland ([GeoTool.isInSwitzerland]), or [WeatherTool]
     * otherwise - this is what actually decides MeteoSwiss vs the generic
     * global model, in plain code, not planTools/the LLM. Returns the
     * [ToolName] that ended up answering (for ToolExecution/the UI) paired
     * with its raw output.
     *
     * [weatherTool]/[swissWeatherTool] each still resolve [query] to
     * coordinates again internally for their own HTTP call - same query, so
     * the same result - rather than taking coordinates as a parameter; that
     * keeps their public API (and existing tests) unchanged.
     */
    private fun weatherFor(query: String, request: ChatRequest, forecast: Boolean): Pair<ToolName, String> {
        val genericTool = if (forecast) ToolName.WEATHER_FORECAST else ToolName.WEATHER
        val coords = geoTool.resolveCoordinates(query, request.latitude, request.longitude)
            ?: return genericTool to """{"error": "Could not determine coordinates for \"$query\"."}"""
        val (latitude, longitude) = coords
        return if (GeoTool.isInSwitzerland(latitude, longitude)) {
            val swissTool = if (forecast) ToolName.SWISS_WEATHER_FORECAST else ToolName.SWISS_WEATHER
            val output = if (forecast) {
                swissWeatherTool.forecast(query, request.latitude, request.longitude)
            } else {
                swissWeatherTool.current(query, request.latitude, request.longitude)
            }
            swissTool to output
        } else {
            val output = if (forecast) {
                weatherTool.forecast(query, request.latitude, request.longitude)
            } else {
                weatherTool.current(query, request.latitude, request.longitude)
            }
            genericTool to output
        }
    }

    private fun resolveLocation(query: String, request: ChatRequest): String {
        if (query != GeoTool.CURRENT_LOCATION) {
            return geoTool.lookup(query)
        }
        val lat = request.latitude
        val lon = request.longitude
        return if (lat != null && lon != null) {
            geoTool.reverseLookup(lat, lon)
        } else {
            """{"error": "Browser did not provide a location for this request."}"""
        }
    }

    private fun searchWeb(query: String, context: OperationContext): String =
        context.ai()
            .withDefaultLlm()
            .withToolGroup(CoreToolGroups.WEB)
            .createObject(
                """
                Search the web for: "$query"
                Report the key facts you found, concisely, as plain text (not JSON).
                """.trimIndent(),
                WebSearchAnswer::class.java,
            ).text

    @Action
    fun summarizeToolResults(
        results: ToolResults,
        intent: MessageIntent,
        context: OperationContext,
    ): ToolSummary {
        if (results.executions.isEmpty()) {
            return ToolSummary("No tools were needed for this message.")
        }
        return context.ai()
            .withDefaultLlm()
            .createObject(
                """
                The user's intent: ${intent.category} (${intent.summary})

                Here is raw output from ${results.executions.size} tool call(s).
                Condense it into a short, plain-language digest of the facts
                relevant to answering the user - drop anything irrelevant,
                and note plainly if a tool failed or found nothing.

                ${results.executions.joinToString("\n\n") {
                    "Tool ${it.tool} (query: \"${it.query}\"):\n${it.rawOutput}"
                }}

                Respond with raw JSON only. Do not wrap it in markdown code
                fences or backticks, and do not add any other text.
                """.trimIndent(),
                ToolSummary::class.java,
            )
    }

    @Action
    fun draftReply(
        summary: ToolSummary,
        request: ChatRequest,
        intent: MessageIntent,
        context: OperationContext,
    ): DraftReply =
        context.ai()
            .withLlmByRole(GENERATION_LLM_ROLE)
            .createObject(
                """
                The user's message was: "${request.message}"
                Detected intent: ${intent.category} (${intent.summary})
                Relevant information gathered from tools: ${summary.summary}

                Draft a helpful, concise reply, using the tool information
                where relevant. If no tool information was needed or
                gathered, just answer directly.
                Respond with raw JSON only. Do not wrap it in markdown code
                fences or backticks, and do not add any other text.
                """.trimIndent(),
                DraftReply::class.java,
            )

    @AchievesGoal(description = "Return a reviewed chat reply to the user")
    @Action
    fun reviewReply(
        draft: DraftReply,
        intent: MessageIntent,
        results: ToolResults,
        context: OperationContext,
    ): ChatReply {
        // Only the text goes through the LLM - it was never given the tool
        // results directly, so asking it to also fill in a toolCalls field
        // would mean it's guessing/hallucinating that part of the JSON.
        // toolCalls is assembled here instead, straight from what
        // executeTools actually ran.
        val reviewed = context.ai()
            .withLlmByRole(GENERATION_LLM_ROLE)
            .createObject(
                """
                Review and polish this draft reply for clarity and tone.
                Keep the same intent (${intent.category}). Return only the final text.
                Draft: "${draft.text}"
                Respond with raw JSON only. Do not wrap it in markdown code
                fences or backticks, and do not add any other text.
                """.trimIndent(),
                ReviewedText::class.java,
            )
        val toolCalls = results.executions.map {
            ToolCallSummary(
                tool = it.tool,
                query = it.query,
                failed = it.rawOutput.trimStart().startsWith("{\"error\""),
                seconds = it.durationMs / 1000.0,
            )
        }
        return ChatReply(reviewed.text, toolCalls)
    }
}

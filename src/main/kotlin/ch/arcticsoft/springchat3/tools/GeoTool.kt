package ch.arcticsoft.springchat3.tools

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Resolves place names and coordinates to real-world locations via Photon
 * (https://photon.komoot.io, https://github.com/komoot/photon), a free,
 * keyless geocoder built on OpenStreetMap data.
 *
 * [lookupPlace] is the LLM-facing tool - annotated with Spring AI's
 * [Tool]/[ToolParam] (`org.springframework.ai.tool.annotation`, part of the
 * `spring-ai-bom` Embabel already pulls in transitively), registered with the
 * model via [ch.arcticsoft.springchat3.agent.ChatAgent.analyzeMessage]'s
 * `context.ai().withToolObject(geoTool)` call. That's real, native tool/
 * function calling - the model invokes this method directly with a real
 * string argument, rather than (as this app used to do) writing a free-text
 * "query" into a hand-rolled enum-tagged plan that application code then
 * parsed and dispatched itself. See [ch.arcticsoft.springchat3.tools.CurrentLocationTool]
 * for the separate "user's own location" tool - deliberately not handled
 * here, so the model never has to invent or guess coordinates for that case.
 *
 * [lookup]/[reverseLookup]/[parseCoordinates] stay plain (unannotated)
 * methods: [lookup] and [reverseLookup] return Photon's raw GeoJSON response
 * as-is (a FeatureCollection - note coordinates are `[longitude, latitude]`,
 * GeoJSON order, not the more common lat/lon order) straight to the
 * answering LLM (see [ch.arcticsoft.springchat3.agent.ChatAgent.answer])
 * rather than parsed here; [parseCoordinates] is a
 * small text-format helper. Keeping them separate from [lookupPlace] (rather
 * than folding their logic inline) keeps them independently unit-testable
 * (see GeoToolTest) and reusable by [CurrentLocationTool].
 *
 * Photon's public instance is free for reasonable/non-commercial use only -
 * if this app sees real traffic, self-host Photon or point baseUrl at a
 * paid/dedicated instance instead (see https://photon.komoot.io).
 *
 * Implements [GatheringTool] purely so [ChatToolRegistry] auto-collects this bean
 * for [ch.arcticsoft.springchat3.agent.ChatAgent.analyzeMessage] - see that
 * interface's doc comment.
 */
@Component
class GeoTool(restClientBuilder: RestClient.Builder) : GatheringTool {

    private val client = restClientBuilder.clone()
        .baseUrl("https://photon.komoot.io")
        .build()

    @Tool(
        name = "lookup_place",
        description = "Look up a named place - resolves it to its coordinates, address, and other " +
            "details via OpenStreetMap-based geocoding. Also accepts explicit coordinates " +
            "(e.g. \"48.9462533, 9.4316044\") if the user pasted them directly - pass them through " +
            "exactly as written rather than rephrasing them. Same for a place name: pass it through " +
            "exactly as the user wrote it - do not correct, translate, or alter its spelling, " +
            "especially accents/umlauts/diacritics, even if you think you know the place and its " +
            "usual spelling; an altered name can resolve to a completely different, unrelated place. " +
            "For the user's OWN current location (\"where am I\", \"what's near me\") use " +
            "get_user_location instead of guessing a place name or coordinates here.",
    )
    fun lookupPlace(
        @ToolParam(description = "A place name (e.g. \"Interlaken\"), or explicit \"latitude, longitude\" coordinates")
        place: String,
    ): String {
        parseCoordinates(place)?.let { (latitude, longitude) -> return reverseLookup(latitude, longitude) }
        return lookup(place)
    }

    // place goes in as a {placeholder} URI variable rather than
    // URLEncoder.encode()-ed and spliced into the string: RestClient's
    // default encoding mode re-encodes whatever's in the template string, so
    // a pre-encoded value here gets double-encoded (%C3%BC -> %25C3%25BC)
    // and any non-ASCII place name like "Zürich" silently fails to resolve.
    // Letting RestClient encode the raw value itself keeps it a single pass.
    fun lookup(place: String): String =
        client.get()
            .uri("/api?q={place}&limit=3&lang=de", place)
            .retrieve()
            .body(String::class.java)
            ?: EMPTY_RESULT

    fun reverseLookup(latitude: Double, longitude: Double): String =
        client.get()
            .uri("/reverse?lon=$longitude&lat=$latitude")
            .retrieve()
            .body(String::class.java)
            ?: EMPTY_RESULT

    /**
     * Parses [query] as an explicit "latitude, longitude" pair if it looks
     * like one - e.g. "48.9462533, 9.4316044", "48.9462533 9.4316044", or
     * "latitude=48.9462533, longitude=9.4316044" (the labels, "lat"/"lon"
     * short forms, "=" or ":", and the comma are all optional and matched
     * case-insensitively). Returns null for anything that doesn't match, or
     * whose numbers fall outside valid latitude/longitude ranges - a normal
     * place name almost never matches this, so [lookupPlace] can check it
     * unconditionally without needing to know in advance whether its
     * argument is a name or a coordinate pair.
     *
     * Exists because a user can paste coordinates straight into a chat
     * message - forward-geocoding that text via [lookup] as if it were a
     * place name (Photon's text search) generally finds nothing, so this
     * lets it be recognized and reverse-geocoded directly instead.
     */
    fun parseCoordinates(query: String): Pair<Double, Double>? {
        val match = COORDINATE_PATTERN.matchEntire(query.trim()) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }

    companion object {
        private const val EMPTY_RESULT = """{"type": "FeatureCollection", "features": []}"""

        // See parseCoordinates: an optional "lat"/"latitude" label (with an
        // optional "=" or ":"), a decimal number, an optional comma/
        // semicolon separator, an optional "lon"/"longitude" label, and a
        // second decimal number - the whole (trimmed) query must match this
        // end to end, not just contain it, so an ordinary place name isn't
        // accidentally swallowed by it.
        private val COORDINATE_PATTERN = Regex(
            """(?:lat(?:itude)?\s*[:=]\s*)?(-?\d{1,3}(?:\.\d+)?)\s*[,;]?\s*(?:lon(?:gitude)?\s*[:=]\s*)?(-?\d{1,3}(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        )
    }
}

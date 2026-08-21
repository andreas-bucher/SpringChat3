package ch.arcticsoft.springchat3.tools

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Resolves place names and coordinates to real-world places via Photon
 * (https://photon.komoot.io), a free, keyless geocoder built on OpenStreetMap
 * data. Two modes:
 *
 *  - lookup(place): forward geocoding, e.g. "Paris" -> matching places
 *  - reverseLookup(lat, lon): reverse geocoding, e.g. coordinates from the
 *    browser's Geolocation API -> the place(s) nearest to them
 *
 * Both return Photon's raw GeoJSON response as-is (a FeatureCollection -
 * note coordinates are [longitude, latitude], GeoJSON order, not the more
 * common lat/lon order). Downstream LLM steps interpret the JSON directly;
 * [WeatherTool]/[SwissWeatherTool] additionally parse coordinates out of it
 * for their own calls, as does [resolveCoordinates] below.
 *
 * Photon's public instance is free for reasonable/non-commercial use only -
 * if this app sees real traffic, self-host Photon or point baseUrl at a
 * paid/dedicated instance instead (see https://photon.komoot.io).
 */
@Component
class GeoTool(restClientBuilder: RestClient.Builder) {

    private val client = restClientBuilder.clone()
        .baseUrl("https://photon.komoot.io")
        .build()

    // Plain instance, not Spring-injected: this is only ever used to walk a
    // JsonNode tree (readTree(...).get(...).asDouble()), which needs no
    // Kotlin-specific modules - keeping GeoTool's constructor unchanged
    // (still just a RestClient.Builder) avoids touching GeoToolTest's
    // `GeoTool(builder)` construction.
    private val objectMapper = ObjectMapper()

    fun lookup(place: String): String {
        // Pass place as a {placeholder} URI variable rather than
        // URLEncoder.encode()-ing it into the string ourselves: RestClient's
        // default encoding mode re-encodes whatever's in the template string,
        // so a pre-encoded value here gets double-encoded (%C3%BC -> %25C3%25BC)
        // and any non-ASCII place name silently fails to resolve. Letting
        // RestClient encode the raw value itself keeps it a single pass.
        return client.get()
            .uri("/api?q={place}&limit=3&lang=de", place)
            .retrieve()
            .body(String::class.java)
            ?: EMPTY_RESULT
    }

    fun reverseLookup(latitude: Double, longitude: Double): String =
        client.get()
            .uri("/reverse?lon=$longitude&lat=$latitude")
            .retrieve()
            .body(String::class.java)
            ?: EMPTY_RESULT

    /**
     * Resolves [query] to coordinates: forward-geocodes a place name via
     * [lookup] (Photon), or - if [query] is the [CURRENT_LOCATION] sentinel -
     * returns [browserLatitude]/[browserLongitude] directly, skipping
     * geocoding entirely. Returns null if coordinates can't be determined
     * either way (unresolvable place name, or CURRENT_LOCATION with no
     * browser coordinates supplied).
     *
     * Used by [ch.arcticsoft.springchat3.agent.ChatAgent.weatherFor] to
     * decide, from the actual resolved location, whether a weather request
     * should go to [WeatherTool] or [SwissWeatherTool] - see
     * [isInSwitzerland]. WeatherTool/SwissWeatherTool each still resolve
     * coordinates again internally for their own HTTP calls (same query, so
     * the same result) rather than taking coordinates as a parameter -
     * keeps their public API unchanged.
     */
    fun resolveCoordinates(query: String, browserLatitude: Double?, browserLongitude: Double?): Pair<Double, Double>? {
        if (query == CURRENT_LOCATION) {
            return if (browserLatitude != null && browserLongitude != null) {
                browserLatitude to browserLongitude
            } else {
                null
            }
        }
        val json = objectMapper.readTree(lookup(query))
        val coordinates = json.get("features")?.firstOrNull()?.get("geometry")?.get("coordinates")
            ?: return null
        // Photon/GeoJSON order is [longitude, latitude] - the reverse of the usual convention.
        val longitude = coordinates.get(0).asDouble()
        val latitude = coordinates.get(1).asDouble()
        return latitude to longitude
    }

    companion object {
        /**
         * Sentinel value planTools uses in a PlannedToolCall.query to mean
         * "the user's actual current location, from the browser" rather
         * than a named place. executeTools/WeatherTool check for this exact
         * string and, if the browser supplied coordinates on the
         * ChatRequest, reverse-geocode / use them directly instead of
         * forward-geocoding this literal string as if it were a place name
         * (which would just fail to resolve).
         */
        const val CURRENT_LOCATION = "CURRENT_LOCATION"

        private const val EMPTY_RESULT = """{"type": "FeatureCollection", "features": []}"""

        // Rough bounding box for Switzerland (not its real, concave border),
        // used by ChatAgent.weatherFor to decide whether a location's weather
        // should come from MeteoSwiss's own regional model instead of the
        // generic global one. A box is intentionally approximate here: a
        // handful of points just across the border may read as "Swiss" or
        // vice versa, which is fine since SwissWeatherTool's ICON-CH models
        // also cover a margin of the immediate neighbours anyway.
        private const val SWITZERLAND_MIN_LATITUDE = 45.75
        private const val SWITZERLAND_MAX_LATITUDE = 47.85
        private const val SWITZERLAND_MIN_LONGITUDE = 5.9
        private const val SWITZERLAND_MAX_LONGITUDE = 10.55

        /** Whether [latitude]/[longitude] fall within (an approximation of) Switzerland. */
        fun isInSwitzerland(latitude: Double, longitude: Double): Boolean =
            latitude in SWITZERLAND_MIN_LATITUDE..SWITZERLAND_MAX_LATITUDE &&
                longitude in SWITZERLAND_MIN_LONGITUDE..SWITZERLAND_MAX_LONGITUDE
    }
}

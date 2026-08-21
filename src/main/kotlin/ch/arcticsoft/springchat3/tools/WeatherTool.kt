package ch.arcticsoft.springchat3.tools

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Current conditions and multi-day forecast via Open-Meteo's free weather
 * API - no API key required (https://open-meteo.com/en/docs).
 *
 * [query] is required, same as [SwissWeatherTool.current]/[SwissWeatherTool.forecast]
 * - it's either a place name (forward-geocoded via [GeoTool]/Photon) or the
 * literal [GeoTool.CURRENT_LOCATION] sentinel, in which case
 * [browserLatitude]/[browserLongitude] - taken straight from the browser's
 * Geolocation API on the ChatRequest - are used directly, skipping
 * geocoding entirely. Either way, if coordinates can't be determined, this
 * returns a `{"error": ...}` JSON string rather than querying Open-Meteo
 * with nothing.
 *
 * Hardening note: [ch.arcticsoft.springchat3.agent.ChatAgent.weatherFor]
 * remains the source of truth for routing WEATHER/WEATHER_FORECAST requests
 * to this tool vs [SwissWeatherTool] - it resolves coordinates once and
 * picks deterministically, so ToolExecution/the UI can record which data
 * source actually answered. This tool additionally checks for itself
 * whether the resolved location falls within Switzerland
 * ([GeoTool.isInSwitzerland]) and, if so, delegates to [SwissWeatherTool]
 * rather than querying Open-Meteo's generic global model - a safety net for
 * any caller that reaches WeatherTool directly instead of through
 * weatherFor's routing. The returned JSON looks the same either way, so
 * this delegation is invisible to (and doesn't change) weatherFor's own
 * routing decision above.
 */
@Component
class WeatherTool(
    restClientBuilder: RestClient.Builder,
    private val geoTool: GeoTool,
    private val objectMapper: ObjectMapper,
    private val swissWeatherTool: SwissWeatherTool,
) {
    private val client = restClientBuilder.clone()
        .baseUrl("https://api.open-meteo.com")
        .build()

    fun current(query: String, browserLatitude: Double? = null, browserLongitude: Double? = null): String =
        forecastFor(query, daily = false, browserLatitude, browserLongitude)

    fun forecast(query: String, browserLatitude: Double? = null, browserLongitude: Double? = null): String =
        forecastFor(query, daily = true, browserLatitude, browserLongitude)

    private fun forecastFor(
        query: String,
        daily: Boolean,
        browserLatitude: Double?,
        browserLongitude: Double?,
    ): String {
        val coords = resolveCoordinates(query, browserLatitude, browserLongitude)
            ?: return """{"error": "Could not determine coordinates for \"$query\"."}"""
        if (GeoTool.isInSwitzerland(coords.first, coords.second)) {
            return if (daily) {
                swissWeatherTool.forecast(query, browserLatitude, browserLongitude)
            } else {
                swissWeatherTool.current(query, browserLatitude, browserLongitude)
            }
        }
        val params = if (daily) {
            "daily=temperature_2m_max,temperature_2m_min,precipitation_sum&timezone=auto&forecast_days=5"
        } else {
            "current_weather=true"
        }
        return client.get()
            .uri("/v1/forecast?latitude=${coords.first}&longitude=${coords.second}&$params")
            .retrieve()
            .body(String::class.java)
            ?: """{"error": "No response from weather API."}"""
    }

    private fun resolveCoordinates(
        query: String,
        browserLatitude: Double?,
        browserLongitude: Double?,
    ): Pair<Double, Double>? {
        if (query == GeoTool.CURRENT_LOCATION) {
            return if (browserLatitude != null && browserLongitude != null) {
                browserLatitude to browserLongitude
            } else {
                null
            }
        }
        val json = objectMapper.readTree(geoTool.lookup(query))
        val coordinates = json.get("features")?.firstOrNull()?.get("geometry")?.get("coordinates")
            ?: return null
        // Photon/GeoJSON order is [longitude, latitude] - the reverse of the usual convention.
        val longitude = coordinates.get(0).asDouble()
        val latitude = coordinates.get(1).asDouble()
        return latitude to longitude
    }
}

package ch.arcticsoft.springchat3.tools

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Current conditions and multi-day forecast straight from MeteoSwiss's own
 * ICON-CH1 / ICON-CH2 numerical weather models, via Open-Meteo's free,
 * keyless API (https://open-meteo.com/en/docs/meteoswiss-api).
 *
 * MeteoSwiss's own open-data platform (https://opendatadocs.meteoswiss.ch/)
 * doesn't expose a simple point-forecast REST endpoint yet - as of writing
 * it only offers manual/automated GRIB & NetCDF downloads, and the docs say
 * a queryable per-location API "will not be available before end of 2026".
 * Open-Meteo mirrors the same MeteoSwiss ICON model output as plain JSON in
 * the meantime, so this tool goes through them instead of MeteoSwiss
 * directly - same free/no-API-key deal as [WeatherTool], and the same
 * `api.open-meteo.com` host, just with `models=` pinned to MeteoSwiss's own
 * models rather than Open-Meteo's default global blend.
 *
 * `meteoswiss_icon_seamless` blends ICON-CH1 (hourly, ~33h lead time, ~1km
 * resolution) with ICON-CH2 (~5 days, ~2km resolution) so the caller doesn't
 * have to pick one. Both models only cover Switzerland and its immediate
 * neighbours - outside that domain Open-Meteo returns an error for them -
 * so this tool is specifically for Swiss locations. [WeatherTool] (Open-
 * Meteo's default worldwide blend) remains the generic fallback for
 * anywhere else.
 *
 * Which tool actually gets called per request is decided deterministically
 * in [ch.arcticsoft.springchat3.agent.ChatAgent.weatherFor], from the
 * resolved location's coordinates ([GeoTool.isInSwitzerland]) - not left to
 * planTools/the LLM to pick, since a small model's judgment on "is this
 * place Swiss?" proved unreliable in practice.
 *
 * [query] is either a place name (forward-geocoded via [GeoTool]/Photon) or
 * the literal [GeoTool.CURRENT_LOCATION] sentinel, in which case
 * [browserLatitude]/[browserLongitude] - taken straight from the browser's
 * Geolocation API on the ChatRequest - are used directly, skipping
 * geocoding entirely. Same convention as [WeatherTool].
 */
@Component
class SwissWeatherTool(
    restClientBuilder: RestClient.Builder,
    private val geoTool: GeoTool,
    private val objectMapper: ObjectMapper,
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
        val params = if (daily) {
            "daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum" +
                "&models=meteoswiss_icon_seamless&timezone=auto&forecast_days=5"
        } else {
            "current_weather=true&models=meteoswiss_icon_seamless"
        }
        return client.get()
            .uri("/v1/forecast?latitude=${coords.first}&longitude=${coords.second}&$params")
            .retrieve()
            .body(String::class.java)
            ?: """{"error": "No response from MeteoSwiss forecast API."}"""
    }

    // Same coordinate-resolution logic as WeatherTool.resolveCoordinates -
    // kept as its own private copy here rather than shared, matching how
    // each tool in this package already owns its full request path end to
    // end (see GeoTool/TransportTool).
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

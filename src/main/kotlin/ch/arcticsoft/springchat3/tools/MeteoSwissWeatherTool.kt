package ch.arcticsoft.springchat3.tools

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Current conditions and a 5-day forecast straight from MeteoSwiss's own
 * ICON-CH1/ICON-CH2 numerical weather models, via Open-Meteo's free, keyless
 * API (https://open-meteo.com/en/docs/meteoswiss-api).
 *
 * MeteoSwiss's own open-data platform (https://opendatadocs.meteoswiss.ch/)
 * doesn't expose a simple point-forecast REST endpoint yet - as of writing
 * it only offers manual/automated GRIB & NetCDF downloads, and the docs say
 * a queryable per-location API "will not be available before end of 2026".
 * Open-Meteo mirrors the same MeteoSwiss ICON model output as plain JSON in
 * the meantime, so this tool goes through them instead of MeteoSwiss
 * directly - same free/no-API-key deal as [GeoTool], and the same
 * `api.open-meteo.com` host, just with `models=` pinned to MeteoSwiss's own
 * models rather than Open-Meteo's default global blend.
 *
 * `meteoswiss_icon_seamless` blends ICON-CH1 (hourly, ~33h lead time, ~1km
 * resolution) with ICON-CH2 (~5 days, ~2km resolution) so the caller doesn't
 * have to pick one. Both models only cover Switzerland and its immediate
 * neighbours - outside that domain Open-Meteo returns an error for them, so
 * [getMeteoSwissWeather]'s own `@Tool` description says as much and tells
 * the model to fall back to general knowledge instead. (A predecessor of
 * this tool, from before this app's native-tool-calling migration, instead
 * had application code decide "is this Swiss?" via a coordinate bounding
 * box and route accordingly - dropped along with the rest of that
 * hand-rolled planning/routing architecture; simplest now to let Open-Meteo
 * itself be the source of truth on model coverage and surface its response
 * straight to the model.)
 *
 * Takes [latitude]/[longitude] directly rather than a place name - resolving
 * a name (or the user's own location) to coordinates is [GeoTool]'s /
 * [CurrentLocationTool]'s job. The model is expected to call `lookup_place`
 * or `get_user_location` first if it doesn't already have coordinates, then
 * pass the result here - the same multi-tool-call pattern already confirmed
 * working end to end for this app's other tools.
 *
 * Fetches current conditions and the 5-day forecast in a single Open-Meteo
 * call, so one `get_meteoswiss_weather` call gives the model enough to
 * answer either a "what's the weather now" or "what's the forecast"
 * question without a second round trip.
 *
 * Implements [ChatTool] purely so [ChatToolRegistry] auto-collects this bean
 * for [ch.arcticsoft.springchat3.agent.ChatAgent.analyzeMessage] - see that
 * interface's doc comment.
 */
@Component
class MeteoSwissWeatherTool(restClientBuilder: RestClient.Builder) : ChatTool {

    private val client = restClientBuilder.clone()
        .baseUrl("https://api.open-meteo.com")
        .build()

    @Tool(
        name = "get_meteoswiss_weather",
        description = "Get current weather conditions and a 5-day forecast from MeteoSwiss's own " +
            "ICON-CH forecast models, for a location given as coordinates. Covers Switzerland and its " +
            "immediate neighbours only - for locations further away this will likely fail, in which " +
            "case answer from general knowledge instead or say weather data isn't available for that " +
            "place, rather than retrying. You need the location's coordinates first: call lookup_place " +
            "(for a named place) or get_user_location (for the user's own location) before this tool " +
            "if you don't already have them.",
    )
    fun getMeteoSwissWeather(
        @ToolParam(description = "Latitude in decimal degrees")
        latitude: Double,
        @ToolParam(description = "Longitude in decimal degrees")
        longitude: Double,
    ): String = try {
        client.get()
            .uri(
                "/v1/forecast?latitude=$latitude&longitude=$longitude" +
                    "&current_weather=true" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum" +
                    "&models=meteoswiss_icon_seamless&timezone=auto&forecast_days=5",
            )
            .retrieve()
            .body(String::class.java)
            ?: """{"error": "No response from MeteoSwiss forecast API."}"""
    } catch (e: HttpClientErrorException) {
        // Open-Meteo returns 400 ({"error":true,"reason":"No data is
        // available for this location"}) when the coordinates fall outside
        // ICON-CH's coverage area (Switzerland + immediate neighbours) - the
        // exact case this tool's own @Tool description warns the model
        // about, but the model can still pick an out-of-range place (e.g.
        // Berlin, 2026-08-22). RestClient's retrieve() throws rather than
        // returning the body for a 4xx response, so without this catch the
        // raw exception message ("400 Bad Request: ...") would otherwise
        // reach the model as if it were a normal successful tool result -
        // Embabel/Spring AI's tool loop feeds whatever text a tool call
        // resolves to back to the model either way, so an uncaught
        // exception here wouldn't fail the request, it would just produce
        // an ugly, unstructured result instead of a clean one. Returning a
        // {"error": ...}-shaped string here also makes isToolError's
        // heuristic (ChatAgent.kt) correctly flag this as a failed call for
        // the UI trace, which the raw exception text did not.
        """{"error": "No MeteoSwiss weather data available for this location - it's likely outside Switzerland and its immediate neighbours, which is all the ICON-CH models cover."}"""
    } catch (e: RestClientException) {
        // Any other failure talking to Open-Meteo (network/timeout/5xx) -
        // distinct from the 4xx coverage case above, so it doesn't get
        // mislabeled as "outside Switzerland" when the real cause is
        // something else entirely.
        """{"error": "MeteoSwiss forecast API request failed: ${e.message}"}"""
    }
}

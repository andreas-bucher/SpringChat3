package ch.arcticsoft.springchat3.tools

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [CurrentLocationTool] - see its class doc comment for why
 * it takes no LLM-supplied parameters and instead closes over per-request
 * browser coordinates at construction time.
 */
class CurrentLocationToolTest {

    private val builder = RestClient.builder()
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    private val geoTool = GeoTool(builder)

    @Test
    fun `getUserCurrentLocation reverse-geocodes the browser coordinates it was constructed with`() {
        server.expect(requestTo("https://photon.komoot.io/reverse?lon=8.5417&lat=47.3769"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(SAMPLE_FEATURE_COLLECTION, MediaType.APPLICATION_JSON))

        val tool = CurrentLocationTool(geoTool, browserLatitude = 47.3769, browserLongitude = 8.5417)
        val result = tool.getUserCurrentLocation()

        assertEquals(SAMPLE_FEATURE_COLLECTION, result)
        server.verify()
    }

    @Test
    fun `getUserCurrentLocation returns an error, not a guess, when the browser shared no location`() {
        val tool = CurrentLocationTool(geoTool, browserLatitude = null, browserLongitude = null)

        val result = tool.getUserCurrentLocation()

        assertTrue(result.contains("\"error\""))
        server.verify() // no HTTP call made
    }

    companion object {
        private const val SAMPLE_FEATURE_COLLECTION =
            """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[8.5417,47.3769]},"properties":{"name":"Zürich"}}]}"""
    }
}

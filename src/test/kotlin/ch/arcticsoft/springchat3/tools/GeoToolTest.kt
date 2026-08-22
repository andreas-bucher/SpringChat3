package ch.arcticsoft.springchat3.tools

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Unit tests for [GeoTool] against a [MockRestServiceServer] - no real
 * network call to Photon is made. Bind the mock server to the same
 * [RestClient.Builder] instance [GeoTool] clones internally, so requests
 * issued by the tool's own client are intercepted.
 *
 * The non-ASCII case (`Zürich`) is regression coverage for a real bug this
 * test caught: [GeoTool.lookup] used to URLEncoder.encode() the place name
 * and splice it into the URI string directly, which RestClient's default
 * encoding mode then re-encoded a second time (%C3%BC -> %25C3%25BC),
 * silently breaking lookups for any non-ASCII place name.
 */
class GeoToolTest {

    private val builder = RestClient.builder()
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    private val geoTool = GeoTool(builder)

    @Test
    fun `lookup calls Photon forward geocoding with URL-encoded place name`() {
        server.expect(requestTo("https://photon.komoot.io/api?q=Z%C3%BCrich&limit=3&lang=de"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(SAMPLE_FEATURE_COLLECTION, MediaType.APPLICATION_JSON))

        val result = geoTool.lookup("Zürich")

        assertEquals(SAMPLE_FEATURE_COLLECTION, result)
        server.verify()
    }

    @Test
    fun `lookup URL-encodes multi-word place names`() {
        // %20, not "+" - lookup() passes place as a {placeholder} URI
        // variable, which RestClient encodes with strict RFC 3986 percent-
        // encoding, not the application/x-www-form-urlencoded convention
        // (where a literal "+" means space) that java.net.URLEncoder uses.
        server.expect(requestTo("https://photon.komoot.io/api?q=New%20York%20City&limit=3&lang=de"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(SAMPLE_FEATURE_COLLECTION, MediaType.APPLICATION_JSON))

        geoTool.lookup("New York City")

        server.verify()
    }

    @Test
    fun `reverseLookup calls Photon reverse geocoding with lon before lat`() {
        server.expect(requestTo("https://photon.komoot.io/reverse?lon=8.5417&lat=47.3769"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(SAMPLE_FEATURE_COLLECTION, MediaType.APPLICATION_JSON))

        val result = geoTool.reverseLookup(latitude = 47.3769, longitude = 8.5417)

        assertEquals(SAMPLE_FEATURE_COLLECTION, result)
        server.verify()
    }

    @Test
    fun `lookup propagates a server error rather than swallowing it`() {
        server.expect(requestTo("https://photon.komoot.io/api?q=Nowhere&limit=3&lang=de"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withServerError())

        assertFailsWith<HttpServerErrorException> { geoTool.lookup("Nowhere") }
        server.verify()
    }

    @Test
    fun `lookupPlace forward-geocodes an ordinary place name`() {
        server.expect(requestTo("https://photon.komoot.io/api?q=Paris&limit=3&lang=de"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(SAMPLE_FEATURE_COLLECTION, MediaType.APPLICATION_JSON))

        val result = geoTool.lookupPlace("Paris")

        assertEquals(SAMPLE_FEATURE_COLLECTION, result)
        server.verify()
    }

    @Test
    fun `lookupPlace reverse-geocodes a pasted coordinate pair instead of forward-geocoding it`() {
        server.expect(requestTo("https://photon.komoot.io/reverse?lon=9.4316044&lat=48.9462533"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(SAMPLE_FEATURE_COLLECTION, MediaType.APPLICATION_JSON))

        val result = geoTool.lookupPlace("48.9462533, 9.4316044")

        assertEquals(SAMPLE_FEATURE_COLLECTION, result)
        server.verify()
    }

    @Test
    fun `parseCoordinates recognizes labeled coordinates`() {
        assertEquals(48.9 to 9.4, geoTool.parseCoordinates("latitude=48.9, longitude=9.4"))
    }

    @Test
    fun `parseCoordinates rejects an ordinary place name`() {
        assertNull(geoTool.parseCoordinates("Paris"))
    }

    @Test
    fun `parseCoordinates rejects out-of-range numbers`() {
        assertNull(geoTool.parseCoordinates("200, 9.4"))
    }

    companion object {
        private const val SAMPLE_FEATURE_COLLECTION =
            """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[8.5417,47.3769]},"properties":{"name":"Zürich"}}]}"""
    }
}

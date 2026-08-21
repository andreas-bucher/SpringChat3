package ch.arcticsoft.springchat3.tools

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Swiss public transport via the free, keyless transport.opendata.ch API
 * (https://transport.opendata.ch/docs.html).
 *
 * Expected query format from the LLM's tool plan: either a single station
 * name for the next departures from that station, or "<from> -> <to>" for a
 * connection search between two stations.
 */
@Component
class TransportTool(restClientBuilder: RestClient.Builder) {

    private val client = restClientBuilder.clone()
        .baseUrl("https://transport.opendata.ch")
        .build()

    fun query(query: String): String {
        val parts = query.split("->").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) {
            connections(parts[0], parts[1])
        } else {
            stationboard(parts.firstOrNull() ?: query)
        }
    }

    // from/to/station go in as {placeholder} URI variables rather than
    // URLEncoder.encode()-ed and spliced into the string: RestClient's
    // default encoding mode re-encodes whatever's in the template string,
    // so a pre-encoded value here gets double-encoded (%C3%A8 -> %25C3%25A8)
    // and any non-ASCII station name like "Genève" or "Neuchâtel" silently
    // fails to resolve. Letting RestClient encode the raw value itself
    // keeps it a single pass.

    private fun connections(from: String, to: String): String =
        client.get()
            .uri("/v1/connections?from={from}&to={to}&limit=3", from, to)
            .retrieve()
            .body(String::class.java)
            ?: """{"connections": []}"""

    private fun stationboard(station: String): String =
        client.get()
            .uri("/v1/stationboard?station={station}&limit=5", station)
            .retrieve()
            .body(String::class.java)
            ?: """{"stationboard": []}"""
}

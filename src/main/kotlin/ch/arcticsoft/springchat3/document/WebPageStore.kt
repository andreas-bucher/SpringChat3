package ch.arcticsoft.springchat3.document

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/**
 * One linked web page (2026-08-23, user's own request "add new section 'WEB
 * PAGES'... similar as 'GOOGLE DRIVE'... Button is Link a Web Page. When
 * clicked a popup opens and the user can enter an url" - see
 * springchat3_projects_panel.md in project memory) - the URL-fetch
 * counterpart to a linked Google Doc ([LinkedGoogleDoc]), minus any Drive/
 * OAuth involvement at all: [ch.arcticsoft.springchat3.web.WebPageController]
 * hands [url] to a self-hosted Firecrawl instance (2026-08-23, superseding
 * this app's own earlier direct-fetch-plus-Jsoup approach - see
 * [ch.arcticsoft.springchat3.web.WebPageController]'s own doc comment for
 * the full history) to fetch+extract as markdown, and ingests that markdown
 * through the exact same [DocumentStore]/[DocumentIndex] pipeline any other
 * document goes through - see
 * [ch.arcticsoft.springchat3.web.WebPageController]'s own doc comment.
 *
 * [documentId] identifies this page in [DocumentStore]/[DocumentIndex]
 * exactly like any other document, the same role [LinkedGoogleDoc.documentId]
 * plays - and changes on every resync for the same reason (a fresh
 * [DocumentStore.store] call each time, with the old entry removed first),
 * which is why this is keyed by [url] for lookups that need to survive a
 * resync ([getByUrl]), not by [documentId] itself.
 *
 * [projectId] is the project that was active when this page was first
 * linked, or null for none - carried forward on every resync rather than
 * re-read from whatever project happens to be active at resync time, same
 * "fixed at link time" reasoning [LinkedGoogleDoc.projectId] follows.
 *
 * No `title` field here, unlike [LinkedGoogleDoc.filename]: a web page's
 * title only exists once Firecrawl has actually fetched+parsed it, and
 * [DocumentStore.get] already holds it (as
 * [ExtractedDocument.filename]) the moment that happens - a second copy here
 * would just be one more place for it to go stale after a resync changes the
 * page's title. [ch.arcticsoft.springchat3.web.WebPageController]'s response
 * DTO reads it from [DocumentStore] instead, same "the ingested document is
 * the source of truth for its own metadata" idea [DriveController]'s
 * `statusResponse()` already applies (it reads a working doc's filename from
 * `documentStore.get(...).filename`, not from [LinkedGoogleDoc.filename] -
 * that field exists there only as a last-known-name fallback for a resync
 * call, which a web page resync doesn't need since it's always keyed by the
 * still-known [url], not by a separately-passed display name).
 */
data class LinkedWebPage(
    val url: String,
    val documentId: String,
    val linkedAt: Long,
    val lastSyncedAt: Long,
    val projectId: String? = null,
)

/**
 * Persists every currently linked [LinkedWebPage] to
 * `[data-dir]/web-pages.json` - same write-through, single-shared-file JSON
 * pattern [WorkingDocumentStore] uses for its own `List<LinkedGoogleDoc>`,
 * just for linked web pages instead of linked Google Docs. A separate store
 * rather than folding into [WorkingDocumentStore] itself: a web page isn't a
 * Google Doc and involves no Drive/OAuth flow at all (see [LinkedWebPage]'s
 * own doc comment) - keeping it as its own type/store/side-panel section
 * avoids stretching [LinkedGoogleDoc]'s Drive-specific fields
 * ([LinkedGoogleDoc.driveFileId]) to cover a case they don't apply to.
 *
 * Write-through, no batching, same simple approach as every other store in
 * this app ([DocumentStore], [WorkingDocumentStore], [DriveLinkStore]) - a
 * load failure at startup (corrupt file) logs a warning and starts with
 * nothing linked rather than failing application startup.
 */
@Component
class WebPageStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
) {
    private val log = LoggerFactory.getLogger(WebPageStore::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var pages: List<LinkedWebPage> = loadPersisted()

    private fun storeFile() = File(dataDir, "web-pages.json")

    private fun loadPersisted(): List<LinkedWebPage> {
        val file = storeFile()
        if (!file.exists()) return emptyList()
        return try {
            objectMapper.readValue<List<LinkedWebPage>>(file)
        } catch (e: Exception) {
            log.warn("Could not load persisted web pages from {} - starting with none linked", file, e)
            emptyList()
        }
    }

    private fun persist() {
        try {
            val file = storeFile()
            file.parentFile?.mkdirs()
            objectMapper.writeValue(file, pages)
        } catch (e: Exception) {
            log.warn("Could not persist web pages to {}", storeFile(), e)
        }
    }

    /** Every currently linked web page. */
    fun getAll(): List<LinkedWebPage> = pages

    /** One linked web page by its current [documentId], or null if [documentId] isn't (or is no longer) one. */
    fun get(documentId: String): LinkedWebPage? = pages.find { it.documentId == documentId }

    /**
     * One linked web page by its [url] - unlike [documentId] (see this
     * class's own doc comment), this survives a resync, so
     * [ch.arcticsoft.springchat3.web.WebPageController.link] uses it to
     * recognize "this URL is already linked" (treating a repeat link of the
     * same URL as a resync rather than a duplicate entry) the same way
     * [WorkingDocumentStore.getByDriveFileId] does for a Google Doc.
     */
    fun getByUrl(url: String): LinkedWebPage? = pages.find { it.url == url }

    /**
     * Adds [page] as a fresh link, or - if [LinkedWebPage.url] already has an
     * entry (a resync, which always produces a new [LinkedWebPage.documentId] -
     * see this class's own doc comment) - replaces that entry with [page]
     * rather than leaving the stale one alongside it. The one write path
     * both a brand new link and a resync of an existing one call.
     */
    fun upsert(page: LinkedWebPage) {
        pages = pages.filterNot { it.url == page.url } + page
        persist()
    }

    /**
     * Drops [documentId]'s link entirely - called by
     * [ch.arcticsoft.springchat3.web.DocumentController.delete] alongside
     * [DriveLinkStore.untrackDocument]/[WorkingDocumentStore.remove] whenever
     * a document's × button is used, a no-op for a [documentId] that was
     * never a linked web page. No lighter-weight "stop tracking but keep the
     * document" case, same reasoning as [WorkingDocumentStore.remove]: a web
     * page's whole reason for being ingested at all was this link.
     */
    fun remove(documentId: String) {
        val updated = pages.filterNot { it.documentId == documentId }
        if (updated.size != pages.size) {
            pages = updated
            persist()
        }
    }
}

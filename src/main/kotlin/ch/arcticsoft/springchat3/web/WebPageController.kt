package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.document.DocumentIndex
import ch.arcticsoft.springchat3.document.DocumentStore
import ch.arcticsoft.springchat3.document.LinkedWebPage
import ch.arcticsoft.springchat3.document.WebPageStore
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.net.URI
import java.time.Duration
import java.util.UUID
import reactor.netty.http.client.HttpClient as ReactorHttpClient

/**
 * `POST /webpages`'s request body - the URL the frontend's "Link a Web
 * Page" popup collected (2026-08-23, user's own request "add new section
 * 'WEB PAGES'... Button is Link a Web Page. When clicked a popup opens and
 * the user can enter an url" - see springchat3_projects_panel.md in project
 * memory). [projectId] is the same "active project at link time" value
 * [LinkFolderRequest.projectId]/[LinkDocRequest.projectId] carry - see
 * [LinkedWebPage.projectId].
 */
data class LinkWebPageRequest(val url: String, val projectId: String? = null)

/**
 * One linked web page's current state, as `GET /webpages` and
 * `POST /webpages`/`POST /webpages/sync/{documentId}` report it - the
 * web-page counterpart to [WorkingDocumentStatus]. [title]/[characterCount]
 * come from [DocumentStore] (via [WebPageController.statusFor]), same "the
 * ingested document is the source of truth for its own metadata" reasoning
 * [WorkingDocumentStatus] already follows for its own `filename`/
 * `characterCount` - see [LinkedWebPage]'s own doc comment for why nothing
 * here is read from [LinkedWebPage] itself beyond [url]/[linkedAt]/
 * [lastSyncedAt]/[projectId].
 */
data class WebPageStatus(
    val documentId: String,
    val url: String,
    val title: String,
    val characterCount: Int,
    val linkedAt: Long,
    val lastSyncedAt: Long,
    val projectId: String? = null,
)

/**
 * `POST {firecrawl.base-url}/v2/scrape`'s request body. [formats] as plain
 * strings (`["markdown"]`) rather than Firecrawl's richer per-format object
 * shape (some formats, e.g. `json`/`screenshot`, take extra options as an
 * object instead of a bare string) - confirmed via Firecrawl's own API
 * reference (docs.firecrawl.dev/api-reference/endpoint/scrape) and a second,
 * independent worked-example source (firecrawl.dev/blog/mastering-firecrawl-scrape-endpoint)
 * before writing this, per this project's own standard for external API
 * shapes (springchat3_native_tool_calling.md risk #6 in project memory) -
 * this app only ever needs plain markdown, so the simpler bare-string form
 * is all that's used here.
 */
private data class FirecrawlScrapeRequest(val url: String, val formats: List<String> = listOf("markdown"))

/**
 * The `data` object inside a successful [FirecrawlScrapeResponse]. Only the
 * two fields this app actually reads are modeled - Firecrawl's real response
 * carries more (`html`, `links`, `screenshot`, etc. depending on
 * [FirecrawlScrapeRequest.formats]) but Jackson silently ignores unmodeled
 * fields by default elsewhere in this app already (no
 * `@JsonIgnoreProperties(ignoreUnknown = false)` anywhere), so there's
 * nothing to gain from modeling them.
 *
 * [metadata] is deliberately `Map<String, Any?>?` rather than a strict data
 * class - one of the two sources checked for this endpoint's shape describes
 * `metadata.title` as possibly a plain string OR a string array depending on
 * the page (unresolved by the second source, which only ever showed the
 * plain-string case in its examples) - a strict `val title: String?` field
 * would throw a hard Jackson deserialization error on whichever shape wasn't
 * anticipated, so [WebPageController.fetchAndIngest] reads `metadata["title"]`
 * defensively instead (`as? String`, falling back to the URL on anything
 * else, array included) rather than trusting either shape outright.
 */
private data class FirecrawlScrapeData(val markdown: String? = null, val metadata: Map<String, Any?>? = null)

/**
 * `POST {firecrawl.base-url}/v2/scrape`'s response body. [error] is only
 * ever populated alongside `success: false` per both sources checked -
 * [WebPageController.fetchAndIngest] surfaces it in [handleWebPageError]'s
 * message when present, same "log/report the real reason when we have one"
 * instinct [DriveController.handleDocSyncError] follows for its own
 * recognized failure cases.
 */
private data class FirecrawlScrapeResponse(val success: Boolean = false, val data: FirecrawlScrapeData? = null, val error: String? = null)

/**
 * Backs index.html's "Web Pages" section (2026-08-23, see [LinkWebPageRequest]'s
 * own doc comment for the originating request) - links/resyncs a plain
 * public URL by handing it to a Firecrawl instance for fetching+markdown
 * extraction, then ingesting that markdown through the exact same
 * [DocumentStore]/[DocumentIndex] pipeline any PDF (uploaded, Drive-synced,
 * or exported from a linked Google Doc - see [DriveController]'s own doc
 * comment) already goes through, so a linked web page participates in
 * chat-Q&A, right-panel project filtering, and deletion identically to any
 * other document.
 *
 * **Superseded design (2026-08-23): this originally did its own direct
 * `WebClient` `GET` against the target URL, extracting text via Jsoup
 * ([ch.arcticsoft.springchat3.document.WebPageTextExtractor], now retired to
 * `_to_delete/`).** That worked for cooperative pages but had three real
 * limitations surfaced by hands-on use: a real first build/link attempt
 * against a retail product page (digitec.ch) 403'd with a generic CDN
 * "Access Denied" body until a realistic browser `User-Agent` was added, no
 * page ever got main-content/boilerplate separation (nav/ads/footers all
 * flowed into the same plain-text blob as the article), and any
 * client-rendered (JS-heavy) page would've returned near-empty content since
 * nothing executed JS. The user's own explicit follow-up ("Integrate with
 * Firecrawl. Firecrawl is running on port 3002") replaced all of that with a
 * self-hosted Firecrawl instance ([firecrawlBaseUrl]/[firecrawlApiKey],
 * backed by `springchat3.firecrawl.*` in application.yml), which does its own
 * fetching (with its own, generally stronger, anti-bot handling) and returns
 * clean markdown with real structure preserved, rather than this app doing
 * either of those itself.
 *
 * **Not modeled on [DriveController] beyond the shared ingest pipeline:** a
 * web page involves no Google account, no OAuth-scoped API, and no Picker -
 * just a `POST` to a fixed local Firecrawl endpoint. [firecrawlApi] is
 * therefore a plain `WebClient` built from the same unqualified,
 * autoconfigured `WebClient.Builder` [DriveController.driveApi] uses (not
 * [ch.arcticsoft.springchat3.config.HttpClientConfig]'s
 * `@Qualifier("aiModelWebClientBuilder")` one, for the same reason
 * [DriveController]'s own doc comment gives - that one's generous timeouts
 * exist specifically for slow local Ollama inference), but pointed at
 * [firecrawlBaseUrl] via `.baseUrl(...)` instead of an arbitrary
 * user-supplied URL - no `.followRedirect(true)` needed here (unlike the
 * retired direct-fetch design), since Firecrawl itself is what follows
 * whatever redirects the *target* page issues, not this app. `.codecs {
 * maxInMemorySize(...) }` mirrors [DriveController.driveApi]'s own fix for
 * the exact same underlying `WebClient` 256 KiB default-buffer limit (see
 * that property's own doc comment for the real incident that motivated it
 * there), sized above [MAX_RESPONSE_BYTES] the same way [DriveController]'s
 * is sized above its own `MAX_PDF_BYTES`. [FETCH_TIMEOUT_SECONDS] is longer
 * than the retired design's own 20s - Firecrawl's own scrape can itself take
 * up to its documented default 60s internally (page load + extraction),
 * before this app's own request even gets a response back.
 *
 * **No content-type/HTML-sniffing check, and no dedicated "page too large"
 * message the way [DriveController.handleDocSyncError] has for Drive's
 * specific `exportSizeLimitExceeded` case** - genuinely v1, deliberately not
 * built out further than what's needed to work for an ordinary public web
 * page: every failure mode (Firecrawl unreachable, `success: false`, blank
 * `markdown`, an oversized response) surfaces through [handleWebPageError] as
 * one generic message rather than a distinguished, friendlier one - revisit
 * if a real linked URL hits it.
 */
@RestController
class WebPageController(
    private val webPageStore: WebPageStore,
    private val documentStore: DocumentStore,
    private val documentIndex: DocumentIndex,
    @Value("\${springchat3.firecrawl.base-url}") private val firecrawlBaseUrl: String,
    @Value("\${springchat3.firecrawl.api-key:}") private val firecrawlApiKey: String,
    webClientBuilder: WebClient.Builder,
) {
    private val log = LoggerFactory.getLogger(WebPageController::class.java)

    companion object {
        // Generous for an ordinary article/page's markdown - see
        // firecrawlApi's own doc comment for why there's no friendlier
        // dedicated message (unlike DriveController.MAX_PDF_BYTES) for a
        // response that exceeds it.
        private const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024 // 10 MB
        private const val FETCH_TIMEOUT_SECONDS = 90L
    }

    private val firecrawlApi = webClientBuilder
        .baseUrl(firecrawlBaseUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                ReactorHttpClient.create()
                    .responseTimeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS)),
            ),
        )
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES + 1024 * 1024) }
        .build()

    /**
     * Combines [page] with its ingested document's current title/character
     * count from [DocumentStore] (see [WebPageStatus]'s own doc comment) -
     * null only in the (not expected in practice) case that [page]'s
     * [LinkedWebPage.documentId] doesn't resolve to a live [DocumentStore]
     * entry, e.g. a crash between [DocumentStore.store] and
     * [WebPageStore.upsert] leaving the two out of sync - [list] simply
     * drops such an entry from the response rather than surfacing a broken
     * row, same defensive pattern [DriveController.statusResponse] already
     * applies via `mapNotNull` for its own folders'/working documents' files.
     */
    private fun statusFor(page: LinkedWebPage): WebPageStatus? =
        documentStore.get(page.documentId)?.let {
            WebPageStatus(page.documentId, page.url, it.filename, it.text.length, page.linkedAt, page.lastSyncedAt, page.projectId)
        }

    @GetMapping("/webpages")
    fun list(): List<WebPageStatus> = webPageStore.getAll().mapNotNull { statusFor(it) }

    /**
     * Links [LinkWebPageRequest.url] and immediately ingests it - same
     * "render with real content right away" reasoning
     * [DriveController.linkDoc] already follows for a Working Document.
     * Idempotent against an already-linked URL, same as
     * [DriveController.linkDoc]'s own idempotency against an already-linked
     * Drive file: if [WebPageStore.getByUrl] already has an entry for this
     * URL, this is simply treated as a resync of that existing entry rather
     * than creating a duplicate one.
     */
    @PostMapping("/webpages")
    fun link(@RequestBody request: LinkWebPageRequest): Mono<ResponseEntity<Any>> {
        val uri = try {
            parseUrl(request.url)
        } catch (e: IllegalArgumentException) {
            return Mono.just(ResponseEntity.badRequest().body(mapOf("message" to (e.message ?: "That doesn't look like a valid URL."))))
        }
        val existing = webPageStore.getByUrl(uri.toString())
        // existing?.projectId wins over the request's - same "a repeat link
        // is a resync, and a resync must not move an already-linked page to
        // whatever project happens to be active now" rule
        // DriveController.linkDoc's own projectId resolution follows.
        val projectId = existing?.projectId ?: request.projectId
        val linkedAt = existing?.linkedAt ?: System.currentTimeMillis()
        return fetchAndIngest(uri, existing?.documentId, linkedAt, projectId)
    }

    /**
     * Re-fetches and re-ingests [documentId]'s web page, always - a manual,
     * single-page action with no changed-vs-unchanged skip check, same
     * "a deliberate click already means pull the latest version" reasoning
     * [DriveController.syncDoc] follows for a Working Document (a plain web
     * page has no `md5Checksum`-style change-detection field the way a
     * binary Drive file does, any more than a Google Doc does). `404 Not
     * Found` if [documentId] isn't (or is no longer) a linked web page.
     */
    @PostMapping("/webpages/sync/{documentId}")
    fun sync(@PathVariable documentId: String): Mono<ResponseEntity<Any>> {
        val existing = webPageStore.get(documentId) ?: return Mono.just(ResponseEntity.notFound().build())
        return fetchAndIngest(URI.create(existing.url), existing.documentId, existing.linkedAt, existing.projectId)
    }

    /**
     * Shared fetch(via Firecrawl)+ingest+bookkeeping sequence [link] (a brand
     * new link or a repeat-URL resync, [existingDocumentId] possibly
     * non-null) and [sync] (always a resync, [existingDocumentId] always
     * non-null) both call - kept as one path so the two endpoints can't drift
     * apart on what "linking"/"resyncing" actually does, same reasoning
     * [DriveController.syncGoogleDocInternal] gives for its own shared path.
     * `Authorization: Bearer ...` is only sent when [firecrawlApiKey] is
     * non-blank (see that property's own doc comment in application.yml) -
     * this app's own self-hosted instance needs no auth at all.
     * `.publishOn(Schedulers.boundedElastic())` shifts [ingestWebPage]'s
     * blocking work (chunking, embedding via [DocumentIndex.index]) off the
     * Netty event-loop thread, same pattern
     * [DriveController.syncGoogleDocInternal] uses for [DriveController.ingestGoogleDoc].
     */
    private fun fetchAndIngest(uri: URI, existingDocumentId: String?, linkedAt: Long, projectId: String?): Mono<ResponseEntity<Any>> {
        log.info("{} web page '{}' via Firecrawl ({})...", if (existingDocumentId == null) "Linking" else "Re-syncing", uri, firecrawlBaseUrl)
        return firecrawlApi.post()
            .uri("/v2/scrape")
            .contentType(MediaType.APPLICATION_JSON)
            .headers { if (firecrawlApiKey.isNotBlank()) it.setBearerAuth(firecrawlApiKey) }
            .bodyValue(FirecrawlScrapeRequest(uri.toString()))
            .retrieve()
            .onStatus(HttpStatusCode::isError) { response ->
                response.bodyToMono(String::class.java)
                    .defaultIfEmpty("")
                    .flatMap { body -> Mono.error(IllegalStateException("Firecrawl scrape of $uri failed: ${response.statusCode()} - $body")) }
            }
            .bodyToMono(FirecrawlScrapeResponse::class.java)
            .publishOn(Schedulers.boundedElastic())
            .flatMap { resp ->
                val markdown = resp.data?.markdown
                if (!resp.success || markdown.isNullOrBlank()) {
                    Mono.error<LinkedWebPage>(
                        IllegalStateException(
                            "Firecrawl could not extract content from $uri" + (resp.error?.let { " - $it" } ?: ""),
                        ),
                    )
                } else {
                    val title = (resp.data.metadata?.get("title") as? String)?.trim()?.ifBlank { null } ?: uri.toString()
                    Mono.just(ingestWebPage(uri.toString(), markdown, title, existingDocumentId, linkedAt, projectId))
                }
            }
            .doOnNext { webPageStore.upsert(it) }
            .map<ResponseEntity<Any>> { page -> statusFor(page)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.internalServerError().build() }
            .onErrorResume { e -> handleWebPageError(e, uri) }
    }

    /**
     * Turns any failure fetching/ingesting [uri] into a `400 Bad Request`
     * with a plain-language `{"message": "..."}` body the frontend displays
     * directly (see index.html's `readErrorMessage`) - deliberately one
     * generic message for every failure mode (Firecrawl unreachable, a
     * non-2xx status from Firecrawl, `success: false`, blank `markdown`, a
     * response over [MAX_RESPONSE_BYTES], etc.) rather than
     * [DriveController.handleDocSyncError]'s one-specific-reason-recognized
     * approach, see the class-level doc comment for why. The real failure is
     * always logged server-side first (with its full stack trace, unlike the
     * client-facing message) so there's still something to diagnose from if
     * a real linked URL hits this repeatedly.
     */
    private fun handleWebPageError(e: Throwable, uri: URI): Mono<ResponseEntity<Any>> {
        log.warn("Could not link/sync web page {} via Firecrawl ({})", uri, firecrawlBaseUrl, e)
        return Mono.just(
            ResponseEntity.badRequest().body(
                mapOf("message" to "Could not fetch \"$uri\" - check the URL is correct and publicly reachable, and that Firecrawl ($firecrawlBaseUrl) is running."),
            ),
        )
    }

    /**
     * Ingests [markdown] (already extracted+cleaned by Firecrawl from [url],
     * with [title] already resolved from Firecrawl's own response metadata -
     * see [fetchAndIngest]) through the exact same [DocumentStore]/
     * [DocumentIndex] pipeline any PDF goes through - the web-page
     * counterpart to [DriveController.ingestGoogleDoc]. If
     * [existingDocumentId] is given (a resync), that older version's
     * document/index entries are removed first, same "replace, don't leave
     * an orphan" handling [DriveController.ingestGoogleDoc] already applies.
     *
     * **No [ch.arcticsoft.springchat3.document.DocumentStructureStore] entry
     * here, unlike every PDF-sourced document type** - structure extraction
     * ([ch.arcticsoft.springchat3.document.DocumentStructureExtractor]) reads
     * a PDF's own embedded outline/bookmarks directly via PDFBox, which a web
     * page never has (there's no PDF at all here - see [DocumentStore.store]'s
     * now-nullable `bytes` parameter) - a linked web page simply always falls
     * back to plain vector search for every question, identically to any
     * bookmark-less PDF (see [ch.arcticsoft.springchat3.document.DocumentStructureStore]'s
     * own doc comment), not a broken or degraded experience.
     *
     * **Removal order matters here too (2026-08-23, see [DocumentStore.remove]'s
     * own doc comment): [documentIndex] before [documentStore]** - no
     * `documentStructureStore.remove` call needed in between, unlike
     * [DriveController.ingestFile]/[DriveController.ingestGoogleDoc], since a
     * web page's older version never had a `structure.json` to begin with.
     *
     * The single-element `pages` list passed to [DocumentIndex.index] uses a
     * fresh random id, not [url]/[existingDocumentId] - [DocumentIndex.index]
     * only reads `page.id` while building its own cleaned [Document] before
     * immediately re-splitting it into synthetic chunk ids anyway (see that
     * method's own doc comment), so nothing downstream actually depends on
     * this id's value, same as [PdfTextExtractor]'s own per-page ids from
     * `PagePdfDocumentReader` are never referenced by identity either.
     */
    private fun ingestWebPage(url: String, markdown: String, title: String, existingDocumentId: String?, linkedAt: Long, projectId: String?): LinkedWebPage {
        existingDocumentId?.let { oldId ->
            documentIndex.remove(oldId)
            documentStore.remove(oldId)
        }
        val documentId = documentStore.store(title, markdown, null, projectId)
        documentIndex.index(documentId, listOf(Document(UUID.randomUUID().toString(), markdown, emptyMap())))
        log.info("Linked web page '{}' ({} markdown chars) as {}", url, markdown.length, documentId)
        return LinkedWebPage(url, documentId, linkedAt, System.currentTimeMillis(), projectId)
    }

    /**
     * Validates [raw] as an absolute `http(s)://` URL, throwing
     * [IllegalArgumentException] (caught by [link], turned into a friendly
     * `400`) otherwise - checked here rather than relying solely on the
     * frontend's `<input type="url">` (client-side validation is a UX
     * convenience, never a substitute for server-side validation of an
     * actual entry point). Deliberately narrow: only the scheme and presence
     * of a host are checked, nothing about reachability - that's what the
     * actual Firecrawl scrape in [fetchAndIngest] finds out.
     */
    private fun parseUrl(raw: String): URI {
        val trimmed = raw.trim()
        val uri = try {
            URI.create(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("That doesn't look like a valid URL.")
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("Only http:// and https:// URLs are supported.")
        }
        return uri
    }
}

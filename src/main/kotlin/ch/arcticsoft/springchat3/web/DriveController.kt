package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.document.DocumentIndex
import ch.arcticsoft.springchat3.document.DocumentStore
import ch.arcticsoft.springchat3.document.DocumentStructureExtractor
import ch.arcticsoft.springchat3.document.DocumentStructureStore
import ch.arcticsoft.springchat3.document.DocumentSummary
import ch.arcticsoft.springchat3.document.DriveLinkStore
import ch.arcticsoft.springchat3.document.DriveSyncedFile
import ch.arcticsoft.springchat3.document.PdfTextExtractor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * `GET /drive/config`'s response - the one piece of Drive-specific config
 * the frontend needs to build a Google Picker itself (see index.html's
 * openDriveFolderPicker). [pickerApiKey] is not a secret the way
 * `GOOGLE_CLIENT_SECRET` is: a Picker "browser key" is meant to be embedded
 * in client-side JS, restricted instead by HTTP referrer in the Cloud
 * Console - see springchat3_google_drive.md in project memory.
 */
data class DriveConfigResponse(val pickerApiKey: String)

/**
 * `GET /drive/picker-token`'s response - a live Google OAuth access token
 * for the Picker widget's own `setOAuthToken`, resolved from this app's own
 * Google sign-in - see [DriveController.pickerToken]'s doc comment for why
 * no separate consent step is needed to get one.
 */
data class DrivePickerTokenResponse(val accessToken: String)

/**
 * `POST /drive/link`'s request body - the folder id/name the frontend's
 * Google Picker callback resolved (see index.html's handlePickerResponse).
 */
data class LinkFolderRequest(val folderId: String, val folderName: String)

/** One linked folder's current state, as [DriveStatusResponse] reports it. */
data class DriveFolderStatus(
    val folderId: String,
    val folderName: String,
    val lastSyncedAt: Long?,
    val files: List<DocumentSummary>,
)

/**
 * Shared response shape for `GET /drive/status` and every
 * `POST /drive/{link,sync/{folderId},unlink/{folderId}}` - always the full
 * current picture of every linked folder, so index.html's
 * renderDriveSection() never has to reconcile a partial update against
 * whatever it already had. A single top-level `linked: Boolean` (v1) doesn't
 * make sense once more than one folder can be linked at once (2026-08-22 -
 * see [DriveController]'s own doc comment) - the frontend derives
 * "nothing linked" from an empty [folders] instead.
 */
data class DriveStatusResponse(val folders: List<DriveFolderStatus> = emptyList())

/**
 * Backs index.html's "Google Drive" section (2026-08-22, user's own request
 * "additionally to upload Documents, it should be possible to link to a
 * Google Drive Folder" - see springchat3_google_drive.md in project memory
 * for the full design, in particular why this uses the `drive.readonly`
 * scope rather than the originally-proposed, narrower `drive.file`, and why
 * [ch.arcticsoft.springchat3.security.SecurityConfig] forces
 * `access_type=offline&prompt=consent` on every Google sign-in).
 *
 * **More than one folder can be linked at once (2026-08-22, same day):**
 * changed from the original "one folder at a time" after the user reported
 * that linking a second folder silently discarded the first one's sync
 * bookkeeping (see [DriveLinkStore]'s own doc comment for the full story).
 * [link]/[sync]/[unlink] are now all scoped to one `folderId` rather than
 * "the" link, and [DriveStatusResponse] reports every linked folder's state
 * at once rather than a single folder-or-nothing shape.
 *
 * **No separate incremental-consent popup, unlike a typical Picker
 * integration:** since this app already gates every request behind a full
 * Google sign-in, and that sign-in's own scope list
 * (application.yml's `spring.security.oauth2.client.registration.google.scope`)
 * already includes `drive.readonly`, the *same* authorized client Spring
 * Security resolves via `@RegisteredOAuth2AuthorizedClient` already carries
 * Drive access - one consent screen covers identity + Drive, not two. The
 * frontend still needs a raw access-token *string* for the Picker widget's
 * `setOAuthToken` (a client-side JS widget, it can't call this app's
 * backend per file request the way [performSync] does server-side) -
 * [pickerToken] just hands out that same authorized client's current access
 * token, transparently refreshed first if needed (automatic, backed by the
 * refresh token `access_type=offline` obtained - see
 * [ch.arcticsoft.springchat3.security.SecurityConfig]'s own doc comment).
 *
 * All Drive REST calls go through a plain, unqualified `WebClient.Builder`
 * (Spring Boot's own autoconfigured default bean) rather than
 * [ch.arcticsoft.springchat3.config.HttpClientConfig]'s
 * `@Qualifier("aiModelWebClientBuilder")` one - that qualifier and its
 * generous timeouts exist specifically for slow local Ollama inference (see
 * that class's own doc comment), not a fast external REST API like Drive's.
 *
 * Plain `WebClient` calls to the Drive v3 REST API, not Google's official
 * (blocking) Java API client library - consistent with this app's existing
 * all-reactive HTTP client patterns (see [ch.arcticsoft.springchat3.config.HttpClientConfig],
 * [ch.arcticsoft.springchat3.tools.MeteoSwissWeatherTool]) rather than
 * introducing a second, blocking way of making HTTP calls. Query/download
 * syntax confirmed against Drive API v3's own reference docs before writing
 * this - not guessed.
 */
@RestController
@RequestMapping("/drive")
class DriveController(
    private val driveLinkStore: DriveLinkStore,
    private val documentStore: DocumentStore,
    private val documentIndex: DocumentIndex,
    private val pdfTextExtractor: PdfTextExtractor,
    private val documentStructureExtractor: DocumentStructureExtractor,
    private val documentStructureStore: DocumentStructureStore,
    webClientBuilder: WebClient.Builder,
    @Value("\${springchat3.google.picker-api-key}") private val pickerApiKey: String,
) {
    private val log = LoggerFactory.getLogger(DriveController::class.java)

    /**
     * `.codecs { maxInMemorySize(...) }` overrides `WebClient`'s default
     * 256 KiB cap on how much of a response body it will buffer in memory
     * before erroring - found the hard way (2026-08-22, a real download of
     * a real PDF from a linked folder, see springchat3_google_drive.md in
     * project memory): `bodyToMono(ByteArray::class.java)` in [downloadFile]
     * failed with `DataBufferLimitException` on anything bigger than that
     * default. [DocumentController.upload]'s direct-upload path never hits
     * this because Spring's multipart `FilePart` is read as a stream, not
     * collected into one in-memory buffer by `WebClient` - this is a
     * `WebClient`-specific limit, not a general "how big a PDF can this app
     * handle" one. Sized comfortably above [MAX_PDF_BYTES] so a file that's
     * actually within this app's own accepted size can always finish
     * downloading - [ingestFile]'s own size check (using the friendlier,
     * app-specific message) is what actually enforces/reports the cap, not
     * this buffer running out first.
     */
    private val driveApi = webClientBuilder
        .baseUrl("https://www.googleapis.com")
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_PDF_BYTES + 1024 * 1024) }
        .build()

    companion object {
        // Same 20 MB cap DocumentController.upload applies to a direct
        // upload - a Drive-sourced PDF gets no special exemption, same
        // reasoning (bounding one file's in-memory footprint).
        private const val MAX_PDF_BYTES = 20 * 1024 * 1024
    }

    @GetMapping("/config")
    fun config(): DriveConfigResponse = DriveConfigResponse(pickerApiKey)

    @GetMapping("/picker-token")
    fun pickerToken(@RegisteredOAuth2AuthorizedClient("google") client: OAuth2AuthorizedClient): DrivePickerTokenResponse =
        DrivePickerTokenResponse(client.accessToken.tokenValue)

    private fun statusResponse(): DriveStatusResponse =
        DriveStatusResponse(
            folders = driveLinkStore.getAll().map { link ->
                val files = link.files.mapNotNull { f ->
                    documentStore.get(f.documentId)?.let { DocumentSummary(f.documentId, it.filename, it.text.length) }
                }
                DriveFolderStatus(
                    folderId = link.folderId,
                    folderName = link.folderName,
                    lastSyncedAt = link.lastSyncedAt,
                    files = files,
                )
            },
        )

    @GetMapping("/status")
    fun status(): DriveStatusResponse = statusResponse()

    /**
     * Links an additional folder (2026-08-22, see this class's own doc
     * comment - previously replaced whatever was already linked) and
     * immediately syncs just that one, so its card renders with real synced
     * files right away rather than an empty "0 PDFs" that only fills in
     * after a separate manual "Sync now" click. Every other already-linked
     * folder is untouched, both in [DriveLinkStore] and in whatever it had
     * already synced.
     */
    @PostMapping("/link")
    fun link(
        @RequestBody request: LinkFolderRequest,
        @RegisteredOAuth2AuthorizedClient("google") client: OAuth2AuthorizedClient,
    ): Mono<DriveStatusResponse> {
        driveLinkStore.link(request.folderId, request.folderName)
        return performSync(client, request.folderId)
    }

    /** Re-syncs just [folderId]. `404 Not Found` if it isn't (or is no longer) linked. */
    @PostMapping("/sync/{folderId}")
    fun sync(
        @PathVariable folderId: String,
        @RegisteredOAuth2AuthorizedClient("google") client: OAuth2AuthorizedClient,
    ): Mono<ResponseEntity<DriveStatusResponse>> =
        if (driveLinkStore.get(folderId) == null) {
            Mono.just(ResponseEntity.notFound().build())
        } else {
            performSync(client, folderId).map { ResponseEntity.ok(it) }
        }

    /**
     * Unlinks just [folderId] (see [DriveLinkStore.unlink]'s own doc comment
     * on why this leaves already-ingested documents, and every other linked
     * folder, alone - same "removes it from the app's index, not from Drive
     * itself" principle the per-file × button follows, just for one whole
     * folder at a time now that more than one can be linked at once).
     */
    @PostMapping("/unlink/{folderId}")
    fun unlink(@PathVariable folderId: String): DriveStatusResponse {
        driveLinkStore.unlink(folderId)
        return statusResponse()
    }

    private data class DriveApiFile(
        val id: String,
        val name: String,
        val modifiedTime: String? = null,
        val md5Checksum: String? = null,
    )

    private data class DriveFileListResponse(val files: List<DriveApiFile> = emptyList())

    /**
     * Google's own error responses (`error.message`, e.g. "Google Drive API
     * has not been used in project ... before or it is disabled" for a
     * disabled API, or "Request had insufficient authentication scopes" for
     * a missing/stale `drive.readonly` grant) are far more actionable than
     * the bare `WebClientResponseException` `.retrieve()` raises by default,
     * which drops the response body entirely - added 2026-08-22 after a
     * real 403 from `/drive/v3/files` showed up in the app log with no body
     * at all, which turned out (once fixed) to be the Google Drive API not
     * being enabled for the Cloud project - see springchat3_google_drive.md
     * in project memory. Reused by both Drive calls below.
     */
    private fun WebClient.ResponseSpec.logDriveErrorBody(): WebClient.ResponseSpec =
        onStatus(HttpStatusCode::isError) { response ->
            response.bodyToMono(String::class.java)
                .defaultIfEmpty("")
                .flatMap { body -> Mono.error(IllegalStateException("Google Drive API request failed: ${response.statusCode()} - $body")) }
        }

    /**
     * Lists every non-trashed PDF directly inside [folderId] (Drive API v3
     * `files.list`, query syntax confirmed against Drive's own reference
     * docs before writing this). Not recursive - a PDF in a *sub*folder of
     * the linked folder is deliberately not picked up (v1, matches the
     * approved mockup's "one linked folder" scope; a `'folderId' in
     * parents` query only ever matches direct children).
     */
    private fun listFolderPdfs(client: OAuth2AuthorizedClient, folderId: String): Mono<List<DriveApiFile>> {
        val query = "mimeType='application/pdf' and trashed=false and '$folderId' in parents"
        return driveApi.get()
            .uri { builder ->
                builder.path("/drive/v3/files")
                    .queryParam("q", query)
                    .queryParam("fields", "files(id,name,modifiedTime,md5Checksum)")
                    .build()
            }
            .headers { it.setBearerAuth(client.accessToken.tokenValue) }
            .retrieve()
            .logDriveErrorBody()
            .bodyToMono(DriveFileListResponse::class.java)
            .map { it.files }
    }

    private fun downloadFile(client: OAuth2AuthorizedClient, fileId: String): Mono<ByteArray> =
        driveApi.get()
            .uri("/drive/v3/files/{id}?alt=media", fileId)
            .headers { it.setBearerAuth(client.accessToken.tokenValue) }
            .retrieve()
            .logDriveErrorBody()
            .bodyToMono(ByteArray::class.java)

    /**
     * Downloads+ingests one Drive file through the exact same pipeline
     * [ch.arcticsoft.springchat3.web.DocumentController.upload] uses for a
     * direct upload (PDFBox page extraction, vector-store indexing, and -
     * best-effort - outline/structure extraction), tagging the result with
     * [DriveApiFile.id] so [DriveLinkStore] can recognize it as already
     * synced next time. If [existingDocumentId] is given (a changed file -
     * different `md5Checksum` - being re-synced), that older version's
     * document/index/structure entries are removed first so it isn't left
     * behind as an orphan alongside the new one.
     *
     * Runs on [Schedulers.boundedElastic] (see [performSync]) - same reason
     * [ch.arcticsoft.springchat3.web.DocumentController.upload] shifts its
     * own PDFBox parsing off the Netty event-loop thread.
     */
    private fun ingestFile(file: DriveApiFile, bytes: ByteArray, existingDocumentId: String?): DriveSyncedFile {
        if (bytes.size > MAX_PDF_BYTES) {
            throw IllegalArgumentException("Drive file '${file.name}' too large (${bytes.size} bytes, max $MAX_PDF_BYTES) - skipped")
        }
        existingDocumentId?.let { oldId ->
            documentStore.remove(oldId)
            documentIndex.remove(oldId)
            documentStructureStore.remove(oldId)
        }
        val pages = pdfTextExtractor.extractPages(bytes)
        val text = pages.joinToString("\n\n") { it.text.orEmpty() }
        val documentId = documentStore.store(file.name, text)
        documentIndex.index(documentId, pages)
        documentStructureExtractor.extractStructure(bytes)?.let { structure ->
            documentStructureStore.store(documentId, structure)
        }
        log.info(
            "Synced Drive file '{}' ({} bytes -> {} extracted chars, {} pages indexed) as {}",
            file.name, bytes.size, text.length, pages.size, documentId,
        )
        return DriveSyncedFile(
            driveFileId = file.id,
            documentId = documentId,
            filename = file.name,
            modifiedTime = file.modifiedTime,
            md5Checksum = file.md5Checksum,
        )
    }

    /**
     * Re-reads [folderId]'s current Drive contents and reconciles this app's
     * ingested documents against them: new files are downloaded+ingested,
     * changed files (`md5Checksum` differs from what was last synced) are
     * re-ingested in place, and files no longer present in the folder
     * (removed/moved/trashed on the Drive side) have their ingested document
     * removed too - "Sync now" always leaves the app's index matching that
     * one folder's *current* contents exactly, same "explicit, no surprises"
     * spirit as the rest of this app (manual upload, manual delete - see
     * [ch.arcticsoft.springchat3.web.DocumentController]). Every *other*
     * linked folder (2026-08-22, see this class's own doc comment) is
     * completely unaffected by one folder's sync - only [folderId]'s own
     * `previousByFileId`/[DriveLinkStore.replaceFiles] are touched.
     *
     * A file whose download/ingest fails (transient network error, etc.)
     * falls back to keeping its *previous* synced entry rather than being
     * dropped - dropping it would make this method wrongly treat it as
     * "removed from the folder" and delete an already-good, previously
     * ingested document over a one-off failure. Only a file genuinely
     * absent from the current Drive listing is treated as removed.
     *
     * Sequential (`concatMap`, not `flatMap`), not parallel: [DocumentIndex]'s
     * `index`/`remove` both rewrite the *entire* shared vector-store file on
     * every call (see that class's own doc comment) - concurrent writes
     * from several files syncing at once would race on that same file.
     * Fine at this feature's expected scale (a personal folder of PDFs). This
     * only serializes within one folder's own sync - two *different* folders
     * being synced concurrently (e.g. one from [link], another from a
     * concurrent [sync] call) would still race on that same vector-store
     * file; not addressed here, same as the pre-existing single-folder
     * version never addressed a concurrent upload racing a sync either.
     */
    private fun performSync(client: OAuth2AuthorizedClient, folderId: String): Mono<DriveStatusResponse> {
        val link = driveLinkStore.get(folderId) ?: return Mono.just(statusResponse())
        val previousByFileId = link.files.associateBy { it.driveFileId }

        return listFolderPdfs(client, link.folderId)
            .flatMapMany { current -> Flux.fromIterable(current) }
            .concatMap { file ->
                val previous = previousByFileId[file.id]
                if (previous != null && previous.md5Checksum != null && previous.md5Checksum == file.md5Checksum) {
                    // Unchanged since the last sync - keep the existing entry, no re-download.
                    Mono.just(previous)
                } else {
                    // Logged here - before the download/extraction/indexing
                    // work even starts, not just on success at the end of
                    // ingestFile() - so a hang or a failure partway through
                    // (large PDF, slow extraction, a network blip) still
                    // shows which file was being processed when it happened,
                    // not just silence until either a success or a warning
                    // with no clue how far it got.
                    log.info(
                        "{} Drive file '{}' (id={})...",
                        if (previous == null) "Downloading new" else "Re-downloading changed",
                        file.name,
                        file.id,
                    )
                    downloadFile(client, file.id)
                        .publishOn(Schedulers.boundedElastic())
                        .map { bytes -> ingestFile(file, bytes, previous?.documentId) }
                        .onErrorResume { e ->
                            log.warn("Could not sync Drive file '{}' - keeping its previous state, if any", file.name, e)
                            Mono.justOrEmpty(previous)
                        }
                }
            }
            .collectList()
            .doOnNext { syncedFiles ->
                // Any previously-synced file that's no longer in the current
                // Drive listing at all was removed/moved/trashed - drop its
                // ingested document too, same "index mirrors the folder"
                // principle as the new/changed-file handling above.
                val currentFileIds = syncedFiles.map { it.driveFileId }.toSet()
                previousByFileId.values
                    .filter { it.driveFileId !in currentFileIds }
                    .forEach { stale ->
                        documentStore.remove(stale.documentId)
                        documentIndex.remove(stale.documentId)
                        documentStructureStore.remove(stale.documentId)
                    }
                driveLinkStore.replaceFiles(folderId, syncedFiles, System.currentTimeMillis())
            }
            .map { statusResponse() }
    }
}

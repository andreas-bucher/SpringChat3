package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.document.DocumentIndex
import ch.arcticsoft.springchat3.document.DocumentStore
import ch.arcticsoft.springchat3.document.DocumentStructureExtractor
import ch.arcticsoft.springchat3.document.DocumentStructureStore
import ch.arcticsoft.springchat3.document.DocumentSummary
import ch.arcticsoft.springchat3.document.DriveLink
import ch.arcticsoft.springchat3.document.DriveLinkStore
import ch.arcticsoft.springchat3.document.DriveSyncedFile
import ch.arcticsoft.springchat3.document.LinkedGoogleDoc
import ch.arcticsoft.springchat3.document.PdfTextExtractor
import ch.arcticsoft.springchat3.document.WorkingDocumentStore
import ch.arcticsoft.springchat3.project.SpaceAccess
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
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
 * [spaceId] (2026-08-23, user's own request "link a google drive folder...
 * then save the files in the project folder of the active project" - see
 * springchat3_projects_panel.md in project memory) is whatever project is
 * active in the frontend's left panel at the moment of linking, or null for
 * none - see [DriveLink.spaceId] for why it's fixed at link time rather
 * than re-read on every later sync.
 */
data class LinkFolderRequest(val folderId: String, val folderName: String, val spaceId: String? = null)

/**
 * `POST /drive/link-doc`'s request body (2026-08-22, "Working Documents" -
 * see springchat3_working_documents.md in project memory) - the Google Doc
 * file id/name the frontend's Google Picker callback resolved (see
 * index.html's handleDocPickerResponse), the individual-file counterpart to
 * [LinkFolderRequest]. [spaceId] is the same "active project at link time"
 * value [LinkFolderRequest.spaceId] carries - see [LinkedGoogleDoc.spaceId].
 */
data class LinkDocRequest(val fileId: String, val fileName: String, val spaceId: String? = null)

/**
 * One linked folder's current state, as [DriveStatusResponse] reports it.
 *
 * [syncing] (2026-08-22, "let the file-by-file sync continue in the
 * background" - see [DriveController]'s own doc comment) is true while a
 * background sync pass ([DriveController.startBackgroundSync]) is currently
 * running for this folder - covers both this folder's very first sync
 * (kicked off by [DriveController.link]) and a later manual
 * [DriveController.sync]. index.html's `buildDriveFolderCard` already had a
 * `folder.syncing` branch before this - originally only ever true for a
 * client-side-only placeholder object shown optimistically while `POST
 * /drive/link`'s response was still in flight (back when that response
 * didn't arrive until the *whole* sync finished) - this field is what makes
 * that same branch reflect genuine, ongoing server-side progress instead,
 * once the frontend starts polling `GET /drive/status` after a now-fast
 * `link`/`sync` response (see index.html's `ensureDriveStatusPolling`).
 *
 * [spaceId] (2026-08-23, user's own request "The right panel shall display
 * the project resources of the selected project of the left panel" - see
 * springchat3_projects_panel.md in project memory) mirrors [DriveLink.spaceId] -
 * index.html's `renderDriveSection` filters against it the same way it
 * filters the plain document list, so a linked folder only shows while its
 * own project is active in the left panel.
 */
data class DriveFolderStatus(
    val folderId: String,
    val folderName: String,
    /**
     * When the folder was linked, as opposed to when it last synced
     * (2026-08-25, sorting - see springchat3_resource_sorting.md in project
     * memory). The two are deliberately different: "newest first" means the
     * order a space was filled in, and sorting on [lastSyncedAt] would
     * reshuffle the whole list every time a sync runs.
     */
    val linkedAt: Long = 0,
    val lastSyncedAt: Long?,
    val files: List<DocumentSummary>,
    val syncing: Boolean = false,
    val spaceId: String? = null,
)

/**
 * One linked Google Doc's current state (2026-08-22, "Working Documents" -
 * see springchat3_working_documents.md in project memory), as
 * [DriveStatusResponse] reports it - the individual-file counterpart to
 * [DriveFolderStatus]. [characterCount] comes from the same
 * [DocumentStore]-backed field [DocumentSummary] already exposes elsewhere,
 * for the same char-count meta line index.html's `formatCharCount()`
 * already renders for every other document type.
 *
 * [driveFileId] (2026-08-22, "add icon to open as Google Doc" - user's own
 * follow-up request) is Drive's own stable file id (see [LinkedGoogleDoc]'s
 * own doc comment for why it's the stable one, unlike [documentId]) - the
 * frontend builds `https://docs.google.com/document/d/{driveFileId}/edit`
 * from it for a new "open the actual Google Doc" link, alongside the
 * existing "open the exported PDF" one every document row already has.
 * Not exposed before this, since nothing on the frontend needed Drive's own
 * id until now - [documentId] alone was enough for every other purpose
 * (selecting, viewing the exported PDF, deleting, resyncing).
 *
 * [spaceId] (2026-08-23, see [DriveFolderStatus.spaceId]'s own doc
 * comment) mirrors [LinkedGoogleDoc.spaceId] - same right-panel filtering
 * role, one level down.
 */
data class WorkingDocumentStatus(
    val documentId: String,
    val filename: String,
    val characterCount: Int,
    /** When the Doc was linked - see [DriveFolderStatus.linkedAt] for why this is not [lastSyncedAt]. */
    val linkedAt: Long = 0,
    val lastSyncedAt: Long,
    val driveFileId: String,
    val spaceId: String? = null,
)

/**
 * Shared response shape for `GET /drive/status` and every
 * `POST /drive/{link,sync/{folderId},unlink/{folderId},link-doc,doc-sync/{documentId}}` -
 * always the full current picture of every linked folder AND every linked
 * Google Doc, so index.html's renderDriveSection()/renderWorkingDocsSection()
 * never have to reconcile a partial update against whatever they already
 * had. A single top-level `linked: Boolean` (v1) doesn't make sense once
 * more than one folder can be linked at once (2026-08-22 - see
 * [DriveController]'s own doc comment) - the frontend derives "nothing
 * linked" from an empty [folders]/[workingDocuments] instead.
 */
data class DriveStatusResponse(
    val folders: List<DriveFolderStatus> = emptyList(),
    val workingDocuments: List<WorkingDocumentStatus> = emptyList(),
)

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
 *
 * **Also backs index.html's "Working Documents" section (2026-08-22, user's
 * own request "Enable to link a Google Doc from Google Drive" - see
 * springchat3_working_documents.md in project memory):** [linkDoc]/[syncDoc]
 * link/resync one individually-picked native Google Doc at a time, tracked
 * by [workingDocumentStore] rather than [driveLinkStore] (a linked Doc isn't
 * inside a folder - see [WorkingDocumentStore]'s own doc comment for why
 * this is a separate store rather than folded into [DriveLink]). The one
 * real mechanical difference from a folder's PDFs: a Google Doc has no raw
 * bytes of its own to download via `files/{id}?alt=media` the way
 * [downloadFile] does for a binary PDF - [exportDocAsPdf] instead calls
 * Drive's `files/{id}/export?mimeType=application/pdf` (confirmed against
 * Drive API v3's own reference docs before writing this, same standard the
 * rest of this class already holds itself to), which renders the Doc's
 * current content as an actual PDF - once exported, [ingestGoogleDoc] feeds
 * those bytes through the *exact* same [PdfTextExtractor]/[DocumentIndex]/
 * [DocumentStructureExtractor] pipeline any other PDF uses, so this feature
 * needed no new extraction code at all. **Not yet confirmed:** whether
 * Google's PDF export actually preserves a Doc's heading structure as PDF
 * bookmarks the way a manually-authored PDF's outline would - if it
 * doesn't, a linked Doc simply falls back to plain vector search for every
 * question, identically to any other bookmark-less PDF (see
 * [DocumentStructureStore]'s own doc comment), not a broken or degraded
 * experience, just one that doesn't get the two-stage structure-search
 * benefit until/unless this is verified and (if needed) addressed.
 *
 * **Confirmed (2026-08-22, in production use): Drive's `export` endpoint has
 * a hard, real size cap** - a large enough Doc 403s with reason
 * `exportSizeLimitExceeded` rather than exporting a (possibly huge) PDF.
 * Unlike the bookmark question above, there's no known workaround via a
 * request parameter - a Doc over the limit simply can't be exported as a
 * PDF at all. [handleDocSyncError] recognizes this specific reason and
 * turns it into a `422` with a plain-language message instead of an opaque
 * `500` (see that method's own doc comment); it does not attempt to link a
 * degraded/partial version of an oversized Doc.
 *
 * **Resync is manual and unconditional, unlike a folder's [performSync]:**
 * the user's own choice, "link + manual resync" over a one-time import -
 * [syncDoc] always re-exports and re-ingests on a deliberate click, with no
 * changed-vs-unchanged skip check the way folder sync's `md5Checksum`
 * comparison has (see [WorkingDocumentStore]'s own doc comment for why - a
 * native Google Doc has no equivalently reliable change-detection field,
 * and a click already means "I want the latest version").
 *
 * **A folder's sync runs in the background, not inline with the HTTP
 * request (2026-08-22).** [link] used to call [performSync] directly and
 * return its result - meaning `POST /drive/link` didn't respond until every
 * PDF in the folder had been downloaded and embedded, one real request that
 * could run for minutes on a folder with several/large files. A real
 * production interruption (the local Ollama embedding call for one file
 * getting cancelled mid-request - see springchat3_google_drive.md in
 * project memory for the incident) exposed the real cost of that design:
 * since nothing was persisted until the *whole* batch finished, the entire
 * sync's progress was lost, not just the one interrupted file. [link] and
 * [sync] now both persist the link/kick off the sync and return
 * immediately via [startBackgroundSync], which tracks in-flight folder ids
 * in [syncingFolderIds] (subscribing [performSync] independently of the
 * request/response lifecycle, so a client disconnecting no longer cancels
 * it) - [DriveFolderStatus.syncing] reports that state, and index.html
 * polls `GET /drive/status` (see `ensureDriveStatusPolling`) to reflect
 * progress as it happens rather than only once, at the very end. [performSync]
 * itself now also persists each file the moment it finishes
 * ([DriveLinkStore.upsertFile]), not just once for the whole batch at the
 * end ([DriveLinkStore.replaceFiles], still used at the end of a pass to
 * prune anything no longer present in Drive) - so a later interruption only
 * loses whatever hadn't completed *yet*, matching the same "don't lose more
 * than necessary" reasoning [performSync]'s own per-file [Mono.onErrorResume]
 * already applied one level up.
 */
@RestController
@RequestMapping("/drive")
class DriveController(
    private val driveLinkStore: DriveLinkStore,
    private val workingDocumentStore: WorkingDocumentStore,
    private val documentStore: DocumentStore,
    private val documentIndex: DocumentIndex,
    private val pdfTextExtractor: PdfTextExtractor,
    private val documentStructureExtractor: DocumentStructureExtractor,
    private val documentStructureStore: DocumentStructureStore,
    webClientBuilder: WebClient.Builder,
    @Value("\${springchat3.google.picker-api-key}") private val pickerApiKey: String,
    private val spaceAccess: SpaceAccess,
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

    /**
     * Folder ids with a background [performSync] currently in flight
     * (2026-08-22, see this class's own doc comment) - purely in-memory,
     * process-local bookkeeping, not persisted like [driveLinkStore]'s own
     * state: it only ever needs to answer "is a sync running right now",
     * which is meaningless across an app restart anyway (any sync in
     * progress when the process stops is gone regardless of what this set
     * says). A plain `ConcurrentHashMap`-backed set rather than anything
     * fancier - this app is a single instance, and the only operations
     * needed are "add if absent" ([MutableSet.add]'s own atomic return
     * value, used by [startBackgroundSync] to avoid starting two overlapping
     * passes for the same folder) and "remove"/"contains".
     */
    private val syncingFolderIds: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

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

    /**
     * [exchange] null means "no caller to filter for" - the internal calls
     * below ([link], [sync], [unlink]) that build a response right after
     * having already checked access for the space they touched. Every
     * *endpoint* passes the real exchange, so nothing a user can call
     * returns another user's folders.
     */
    private fun statusResponse(exchange: ServerWebExchange? = null): DriveStatusResponse =
        DriveStatusResponse(
            folders = driveLinkStore.getAll().filter { canSee(exchange, it.spaceId) }.map { link ->
                val files = link.files.mapNotNull { f ->
                    documentStore.get(f.documentId)?.let {
                        DocumentSummary(f.documentId, it.filename, it.text.length, it.spaceId, it.uploadedAt)
                    }
                }
                DriveFolderStatus(
                    folderId = link.folderId,
                    folderName = link.folderName,
                    linkedAt = link.linkedAt,
                    lastSyncedAt = link.lastSyncedAt,
                    files = files,
                    syncing = link.folderId in syncingFolderIds,
                    spaceId = link.spaceId,
                )
            },
            workingDocuments = workingDocumentStore.getAll().filter { canSee(exchange, it.spaceId) }.mapNotNull { doc ->
                documentStore.get(doc.documentId)?.let {
                    WorkingDocumentStatus(
                        doc.documentId,
                        it.filename,
                        it.text.length,
                        doc.linkedAt,
                        doc.lastSyncedAt,
                        doc.driveFileId,
                        doc.spaceId,
                    )
                }
            },
        )

    private fun canSee(exchange: ServerWebExchange?, spaceId: String?): Boolean =
        exchange == null || spaceAccess.canRead(exchange, spaceId)

    @GetMapping("/status")
    fun status(exchange: ServerWebExchange): DriveStatusResponse = statusResponse(exchange)

    /**
     * Links an additional folder (2026-08-22, see this class's own doc
     * comment - previously replaced whatever was already linked) and starts
     * syncing just that one in the background (2026-08-22, see this class's
     * own doc comment on why - [startBackgroundSync]), so its card renders
     * with real synced files as they come in rather than an empty "0 PDFs"
     * that only fills in after a separate manual "Sync now" click. Every
     * other already-linked folder is untouched, both in [DriveLinkStore] and
     * in whatever it had already synced. Responds as soon as the link itself
     * is recorded - not, as before, only once the whole first sync finishes.
     */
    @PostMapping("/link")
    fun link(
        @RequestBody request: LinkFolderRequest,
        @RegisteredOAuth2AuthorizedClient("google") client: OAuth2AuthorizedClient,
        exchange: ServerWebExchange,
    ): DriveStatusResponse {
        spaceAccess.requireWrite(exchange, request.spaceId)
        driveLinkStore.link(request.folderId, request.folderName, request.spaceId)
        startBackgroundSync(client, request.folderId)
        return statusResponse(exchange)
    }

    /**
     * Kicks off (or resyncs) just [folderId] in the background.
     * `404 Not Found` if it isn't (or is no longer) linked. Responds as soon
     * as the sync starts (2026-08-22, see this class's own doc comment),
     * not once it finishes - index.html polls `GET /drive/status` (see
     * `ensureDriveStatusPolling`) to find out when it does.
     */
    @PostMapping("/sync/{folderId}")
    fun sync(
        @PathVariable folderId: String,
        @RegisteredOAuth2AuthorizedClient("google") client: OAuth2AuthorizedClient,
        exchange: ServerWebExchange,
    ): ResponseEntity<DriveStatusResponse> {
        val link = driveLinkStore.get(folderId) ?: return ResponseEntity.notFound().build()
        // Resolved from the link, not from the caller - a folder id is
        // guessable in exactly the way a document id is, see
        // DocumentController.file.
        spaceAccess.requireWrite(exchange, link.spaceId)
        startBackgroundSync(client, folderId)
        return ResponseEntity.ok(statusResponse(exchange))
    }

    /**
     * Subscribes [performSync] for [folderId] independently of whatever HTTP
     * request triggered it (2026-08-22, see this class's own doc comment),
     * so the sync keeps running even if that request's own response has
     * already gone out (or the client that made it has since disconnected -
     * the original failure mode this exists to fix). [syncingFolderIds]'s
     * atomic "add if absent" guards against starting a second overlapping
     * pass for a folder that's already syncing - e.g. a double-click on
     * "Sync now", or a manual sync landing while the just-triggered [link]
     * sync for the same folder is still running (unlikely, since a freshly
     * linked folder wouldn't have a "Sync now" button rendered yet while its
     * card still shows [DriveFolderStatus.syncing], but not impossible under
     * a raw API call) - a no-op in that case, not an error, since a sync IS
     * already in progress, which is what the caller wanted regardless of
     * who started it. Errors are logged, not surfaced anywhere else, since
     * nothing is awaiting this call's own completion - a failed pass simply
     * leaves [DriveFolderStatus.syncing] false again with whatever files had
     * already been persisted via [DriveLinkStore.upsertFile] before the
     * failure, same partial-progress outcome [performSync]'s own per-file
     * [Mono.onErrorResume] already produces for one bad file within an
     * otherwise-successful pass.
     *
     * **Known edge case, not handled:** [syncingFolderIds] is keyed by
     * [folderId] alone, not by "this particular sync attempt" - unlinking a
     * folder and immediately relinking the *same* Drive folder while its
     * original sync is still running will find [folderId] still present in
     * [syncingFolderIds] (not yet removed by the old pass's [doFinally]) and
     * skip starting a fresh one, even though [DriveLinkStore.link] just
     * created a brand new entry for it - the still-running old pass's own
     * [DriveLinkStore.upsertFile]/[DriveLinkStore.replaceFiles] calls will
     * keep writing into that new entry instead (they only check "is
     * [folderId] linked at all", which it is again), against a Drive
     * listing snapshot taken before the relink. Requires unlinking and
     * relinking the same folder within the window its own sync is still
     * running to trigger at all - not addressed here, since nothing this
     * app has seen so far has hit it; would need per-attempt identity (e.g.
     * a generation counter alongside each [folderId]) rather than a plain
     * set, if it ever does.
     */
    private fun startBackgroundSync(client: OAuth2AuthorizedClient, folderId: String) {
        if (!syncingFolderIds.add(folderId)) return
        performSync(client, folderId)
            .doFinally { syncingFolderIds.remove(folderId) }
            .subscribe(
                {},
                { e -> log.error("Background sync of Drive folder '{}' failed", folderId, e) },
            )
    }

    /**
     * Unlinks just [folderId] (see [DriveLinkStore.unlink]'s own doc comment
     * on why this leaves already-ingested documents, and every other linked
     * folder, alone - same "removes it from the app's index, not from Drive
     * itself" principle the per-file × button follows, just for one whole
     * folder at a time now that more than one can be linked at once).
     */
    @PostMapping("/unlink/{folderId}")
    fun unlink(@PathVariable folderId: String, exchange: ServerWebExchange): DriveStatusResponse {
        driveLinkStore.get(folderId)?.let { spaceAccess.requireWrite(exchange, it.spaceId) }
        driveLinkStore.unlink(folderId)
        return statusResponse(exchange)
    }

    /**
     * Links [LinkDocRequest.fileId] as a Working Document (2026-08-22, see
     * this class's own doc comment) and immediately ingests it - same
     * "render with real content right away, not an empty placeholder that
     * only fills in after a separate manual sync" reasoning [link] already
     * follows for a folder. Idempotent against an already-linked Drive file,
     * same as [DriveLinkStore.link]'s own idempotency: if
     * [WorkingDocumentStore.getByDriveFileId] already has an entry for this
     * [LinkDocRequest.fileId] (e.g. the Picker was used to pick the same Doc
     * twice), this is simply treated as a resync of that existing entry
     * rather than creating a duplicate one - [WorkingDocumentStore.upsert]
     * replaces by `driveFileId`, so no special-casing is needed here beyond
     * looking up whether an existing entry's [LinkedGoogleDoc.documentId]/
     * [LinkedGoogleDoc.linkedAt] should carry forward.
     */
    @PostMapping("/link-doc")
    fun linkDoc(
        @RequestBody request: LinkDocRequest,
        @RegisteredOAuth2AuthorizedClient("google") client: OAuth2AuthorizedClient,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Any>> {
        val existing = workingDocumentStore.getByDriveFileId(request.fileId)
        // Checked against the space this actually lands in - `existing`'s
        // when it is a resync (see below), the request's otherwise.
        spaceAccess.requireWrite(exchange, existing?.spaceId ?: request.spaceId)
        // existing?.spaceId wins over the request's: a repeat pick of an
        // already-linked Doc is a resync (see this method's own doc
        // comment), and a resync must not move an already-linked Doc to
        // whatever project happens to be active now - same "fixed at link
        // time" rule LinkFolderRequest.spaceId/DriveLink.spaceId follow.
        val spaceId = existing?.spaceId ?: request.spaceId
        return syncGoogleDocInternal(client, request.fileId, request.fileName, existing, spaceId, exchange)
    }

    /**
     * Re-exports and re-ingests [documentId]'s Google Doc, always - a
     * manual, single-document action, unlike folder sync's changed-vs-
     * unchanged reconciliation across every file in a folder (see this
     * class's own doc comment for why there's no skip-if-unchanged check
     * here). `404 Not Found` if [documentId] isn't (or is no longer) a
     * linked Working Document.
     */
    @PostMapping("/doc-sync/{documentId}")
    fun syncDoc(
        @PathVariable documentId: String,
        @RegisteredOAuth2AuthorizedClient("google") client: OAuth2AuthorizedClient,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Any>> {
        val existing = workingDocumentStore.get(documentId) ?: return Mono.just(ResponseEntity.notFound().build())
        spaceAccess.requireWrite(exchange, existing.spaceId)
        return syncGoogleDocInternal(client, existing.driveFileId, existing.filename, existing, existing.spaceId, exchange)
    }

    /**
     * Shared export+ingest+bookkeeping sequence [linkDoc] (a brand new link,
     * [existing] null) and [syncDoc] (a resync, [existing] the current
     * entry) both call - kept as one path so the two endpoints can't drift
     * apart on what "linking"/"resyncing" actually does. Runs on
     * [Schedulers.boundedElastic] for the same reason [performSync]'s own
     * per-file ingestion does - PDFBox parsing (inside [ingestGoogleDoc]) is
     * blocking CPU work, not something to run on a Netty event-loop thread.
     *
     * Returns `ResponseEntity<Any>` rather than the plain [DriveStatusResponse]
     * both endpoints used to return directly - added 2026-08-22 after a real
     * Doc ("EDIT - Designing and Building AI Products and Services") 403'd
     * on export with Google's `exportSizeLimitExceeded` reason (a real,
     * permanent Drive API cap on how large a rendered export can be - not a
     * transient failure, and not something a request parameter can raise).
     * Before this fix that surfaced to the browser as a bare "Server
     * responded with 500" with the actual reason buried in the server log -
     * [handleDocSyncError] below turns that one specific, recognizable
     * failure into a `422` with a human-readable body instead, while letting
     * every other error (network blip, revoked auth, disabled API, etc.)
     * keep propagating as a plain 500 exactly as before, consistent with how
     * [logDriveErrorBody] already treats those as adequately actionable via
     * the logged response body.
     */
    private fun syncGoogleDocInternal(
        client: OAuth2AuthorizedClient,
        fileId: String,
        fileName: String,
        existing: LinkedGoogleDoc?,
        spaceId: String?,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Any>> {
        val linkedAt = existing?.linkedAt ?: System.currentTimeMillis()
        log.info(
            "{} Google Doc '{}' (id={})...",
            if (existing == null) "Linking" else "Re-syncing",
            fileName,
            fileId,
        )
        return exportDocAsPdf(client, fileId)
            .publishOn(Schedulers.boundedElastic())
            .map { bytes -> ingestGoogleDoc(fileId, fileName, bytes, existing?.documentId, linkedAt, spaceId) }
            .doOnNext { workingDocumentStore.upsert(it) }
            .map<ResponseEntity<Any>> { ResponseEntity.ok(statusResponse(exchange)) }
            .onErrorResume { e -> handleDocSyncError(e, fileName) }
    }

    /**
     * Recognizes Google Drive's `exportSizeLimitExceeded` reason (see
     * [syncGoogleDocInternal]'s own doc comment) inside the raw error string
     * [logDriveErrorBody] raises, and turns it into a `422 Unprocessable
     * Entity` with a plain-language `{"message": "..."}` body the frontend
     * displays directly (see index.html's `readErrorMessage`) - matched by
     * substring against the Drive API's own JSON error body rather than a
     * dedicated exception type, since [logDriveErrorBody] only ever raises a
     * generic [IllegalStateException] carrying that body as its message; not
     * worth a structured JSON-parse of Google's error shape for one
     * recognized reason string. Any other error (a substring match miss)
     * is re-raised unchanged and falls through to the default 500, same as
     * before this fix existed.
     */
    private fun handleDocSyncError(e: Throwable, fileName: String): Mono<ResponseEntity<Any>> =
        if (e.message?.contains("exportSizeLimitExceeded") == true) {
            log.warn("Google Doc '{}' is too large for Drive to export as a PDF - not linked/synced", fileName)
            Mono.just(
                ResponseEntity.unprocessableEntity().body(
                    mapOf(
                        "message" to
                            "\"$fileName\" is too large for Google Drive to export as a PDF, so it can't be linked. " +
                            "Try splitting it into smaller Docs, or exporting/uploading a PDF of it manually instead.",
                    ),
                ),
            )
        } else {
            Mono.error(e)
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
     * Renders [fileId]'s CURRENT content as a PDF (2026-08-22, "Working
     * Documents" - see this class's own doc comment) - Drive API v3's
     * `files/{id}/export`, not the `alt=media` [downloadFile] uses for a
     * binary file already stored in Drive. A native Google Doc has no fixed
     * bytes of its own to download that way (`alt=media` on one 400s/403s -
     * Google's own docs are explicit that native-format files must be
     * exported, not downloaded directly); `export` instead asks Drive to
     * render the Doc, at the moment of the call, into a chosen format - here
     * `application/pdf`, so [ingestGoogleDoc] can feed the result through
     * the exact same PDF pipeline every other document in this app already
     * uses, no separate text-extraction path needed. This is also exactly
     * why resync (unlike a folder's `md5Checksum`-diffed [performSync])
     * always re-exports unconditionally: every call renders the Doc fresh,
     * so there's no stable checksum to compare against between one export
     * and the next the way a binary file's bytes would give you.
     */
    private fun exportDocAsPdf(client: OAuth2AuthorizedClient, fileId: String): Mono<ByteArray> =
        driveApi.get()
            .uri("/drive/v3/files/{id}/export?mimeType=application/pdf", fileId)
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
     * Takes the whole [link] (2026-08-23, changed alongside
     * [DriveLink.driveFolderLocalId] - see that field's own doc comment)
     * rather than just its `spaceId`, now that a synced file's storage
     * location depends on two of the link's own fields together
     * ([DriveLink.spaceId] and [DriveLink.driveFolderLocalId]) - passing
     * the whole object avoids two parallel parameters that could drift out
     * of sync with each other at a call site.
     *
     * Runs on [Schedulers.boundedElastic] (see [performSync]) - same reason
     * [ch.arcticsoft.springchat3.web.DocumentController.upload] shifts its
     * own PDFBox parsing off the Netty event-loop thread.
     */
    private fun ingestFile(file: DriveApiFile, bytes: ByteArray, existingDocumentId: String?, link: DriveLink): DriveSyncedFile {
        if (bytes.size > MAX_PDF_BYTES) {
            throw IllegalArgumentException("Drive file '${file.name}' too large (${bytes.size} bytes, max $MAX_PDF_BYTES) - skipped")
        }
        existingDocumentId?.let { oldId ->
            // documentIndex/documentStructureStore before documentStore (2026-08-23,
            // see DocumentStore.remove's own doc comment): both resolve oldId's
            // directory via documentStore.documentDir(oldId), which returns null
            // once documentStore's own entry is gone.
            documentIndex.remove(oldId)
            documentStructureStore.remove(oldId)
            documentStore.remove(oldId)
        }
        val pages = pdfTextExtractor.extractPages(bytes)
        val text = pages.joinToString("\n\n") { it.text.orEmpty() }
        val documentId = documentStore.store(file.name, text, bytes, link.spaceId, link.driveFolderLocalId)
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
     * Ingests one exported Google Doc PDF through the exact same pipeline
     * [ingestFile] uses for a folder-synced PDF (PDFBox page extraction,
     * vector-store indexing, best-effort outline/structure extraction) -
     * see [exportDocAsPdf]'s own doc comment for how [bytes] gets here.
     * [existingDocumentId] (given on a resync, see [syncGoogleDocInternal])
     * has its older version's document/index/structure entries removed
     * first, same "replace, don't leave an orphan" handling [ingestFile]
     * already applies to a changed folder-synced PDF - the difference here
     * is that EVERY resync takes this path (no unchanged-skip check, see
     * this class's own doc comment), not just a detected-as-changed one.
     * [linkedAt] is threaded through from the caller rather than computed
     * here, since only the caller knows whether this is a brand new link
     * (now) or a resync of an existing one (its original [LinkedGoogleDoc.linkedAt]) -
     * [documentId] itself is always freshly generated either way (see
     * [WorkingDocumentStore]'s own doc comment for why that's fine, since
     * lookups needing to survive a resync key off [driveFileId]/[fileId]
     * instead).
     */
    private fun ingestGoogleDoc(fileId: String, fileName: String, bytes: ByteArray, existingDocumentId: String?, linkedAt: Long, spaceId: String?): LinkedGoogleDoc {
        if (bytes.size > MAX_PDF_BYTES) {
            throw IllegalArgumentException("Google Doc '$fileName' too large once exported as PDF (${bytes.size} bytes, max $MAX_PDF_BYTES) - skipped")
        }
        existingDocumentId?.let { oldId ->
            // Same removal order as ingestFile - see its own comment.
            documentIndex.remove(oldId)
            documentStructureStore.remove(oldId)
            documentStore.remove(oldId)
        }
        val pages = pdfTextExtractor.extractPages(bytes)
        val text = pages.joinToString("\n\n") { it.text.orEmpty() }
        val documentId = documentStore.store(fileName, text, bytes, spaceId)
        documentIndex.index(documentId, pages)
        documentStructureExtractor.extractStructure(bytes)?.let { structure ->
            documentStructureStore.store(documentId, structure)
        }
        log.info(
            "Synced Google Doc '{}' ({} exported PDF bytes -> {} extracted chars, {} pages indexed) as {}",
            fileName, bytes.size, text.length, pages.size, documentId,
        )
        return LinkedGoogleDoc(
            driveFileId = fileId,
            documentId = documentId,
            filename = fileName,
            linkedAt = linkedAt,
            lastSyncedAt = System.currentTimeMillis(),
            spaceId = spaceId,
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
     * **Persists incrementally, not just once at the end (2026-08-22, see
     * this class's own doc comment):** each freshly ingested/re-ingested
     * file is written via [DriveLinkStore.upsertFile] the moment it
     * completes, inside the `concatMap` below - not only via the single
     * [DriveLinkStore.replaceFiles] call after every file in the folder has
     * been processed. [replaceFiles] still runs at the end (needed either
     * way, to prune anything no longer present in Drive - a per-file
     * upsert can't know that on its own), but a background pass that gets
     * interrupted or killed partway through - the whole reason this method
     * now runs detached from its triggering request, see [startBackgroundSync] -
     * only loses whatever hadn't finished *yet*, not everything.
     *
     * Sequential (`concatMap`, not `flatMap`), not parallel: [DocumentIndex]'s
     * `index`/`remove` both rewrite the *entire* shared vector-store file on
     * every call (see that class's own doc comment) - concurrent writes
     * from several files syncing at once would race on that same file.
     * Fine at this feature's expected scale (a personal folder of PDFs). This
     * only serializes within one folder's own sync - two *different* folders
     * syncing concurrently (e.g. one from [link], another from a concurrent
     * [sync] call) would still race on that same vector-store file; more
     * likely to actually happen now that both endpoints return immediately
     * rather than blocking (see this class's own doc comment) instead of
     * needing two overlapping slow requests, but still not addressed here,
     * same as the pre-existing single-folder version never addressed a
     * concurrent upload racing a sync either.
     */
    private fun performSync(client: OAuth2AuthorizedClient, folderId: String): Mono<DriveStatusResponse> {
        // The two unfiltered statusResponse() calls in here are deliberate:
        // performSync runs detached from any request (see startBackgroundSync)
        // and its result is discarded, so there is no caller to filter for -
        // which is exactly the case statusResponse's null exchange covers.
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
                        .map { bytes -> ingestFile(file, bytes, previous?.documentId, link) }
                        .doOnNext { synced -> driveLinkStore.upsertFile(folderId, synced) }
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
                        // Same removal order as ingestFile/ingestGoogleDoc
                        // (2026-08-23, see DocumentStore.remove's own doc
                        // comment) - this block had the old, wrong order
                        // (documentStore.remove first) until caught here.
                        documentIndex.remove(stale.documentId)
                        documentStructureStore.remove(stale.documentId)
                        documentStore.remove(stale.documentId)
                    }
                driveLinkStore.replaceFiles(folderId, syncedFiles, System.currentTimeMillis())
            }
            .map { statusResponse() }
    }
}

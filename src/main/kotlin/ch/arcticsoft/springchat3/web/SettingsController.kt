package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.project.SpaceAccess
import ch.arcticsoft.springchat3.security.Admins
import ch.arcticsoft.springchat3.settings.AppSettingsStore
import ch.arcticsoft.springchat3.settings.ModelRoleKeys
import ch.arcticsoft.springchat3.settings.SettingsResolver
import ch.arcticsoft.springchat3.settings.UserSettingsStore
import org.slf4j.LoggerFactory
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

/**
 * The four selectable roles' *currently active* model **for the caller**
 * (2026-08-25 - was one global answer until the per-user split; see
 * [SettingsResolver] for the resolution order).
 */
data class SettingsModels(
    val toolSelection: String,
    val documentSearchStrategy: String,
    val documentEdit: String,
    val generation: String,
)

/**
 * [SettingsController]'s response shape for `GET /settings` and every `POST`.
 * Caller-specific since 2026-08-25: two people signed in at once get
 * different [models]/[toolsEnabled] out of the same endpoint.
 */
data class SettingsResponse(
    val models: SettingsModels,
    val embeddingModel: String,
    /** What the dropdowns may offer - the installed models the allow-list permits, plus the configured defaults. */
    val availableModels: List<String>,
    /** Everything the local Ollama actually has. Only the admin section's allow-list checklist uses this. */
    val installedModels: List<String>,
    /** The curated allow-list as stored; empty means "no curation". Only meaningful to an admin. */
    val allowedModels: List<String>,
    /** The caller's own effective value, not the server default. */
    val toolsEnabled: Boolean,
    /** Server policy, the same for everyone - changeable only by an admin. */
    val documentEditingEnabled: Boolean,
    /** Whether this caller may change the two policy settings. The UI hides that section; the POSTs enforce it. */
    val admin: Boolean,
)

/** `POST /settings/tools`'s and `POST /settings/document-editing`'s shared request body. */
data class ToolsEnabledRequest(val enabled: Boolean)

/**
 * `POST /settings/model`'s request body - [role] is one of [ModelRoleKeys]'s
 * constants, [model] the exact Ollama tag to use for it from now on, or
 * null/blank to clear the caller's own override and fall back to the server
 * default for that role.
 */
data class ModelOverrideRequest(val role: String, val model: String?)

/** `POST /settings/allowed-models`'s request body - the full new list, empty for "no curation". */
data class AllowedModelsRequest(val models: List<String> = emptyList())

/**
 * Backs index.html's settings popup (2026-08-22, see springchat3_settings.md
 * in project memory).
 *
 * **Two kinds of setting since 2026-08-25** (user's own question: "would it
 * not make more sense to make the settings per user"). Preferences - the four
 * model dropdowns and the tool-use toggle - are the caller's own and are
 * written to [UserSettingsStore]. Policy - document editing and the model
 * allow-list - stays one value for the server and is admin-only ([Admins]),
 * because both reach past the person flipping them: the agent's edits land in
 * documents other people share, and every model runs on one Ollama host.
 *
 * The caller is read the same way every other controller reads it, from the
 * exchange attribute [ch.arcticsoft.springchat3.security.CurrentUserWebFilter]
 * sets - so `/settings` is no longer identity-free and is no longer one of
 * the routes exempt from knowing who is asking (see springchat3_multi_user.md).
 *
 * **Why the embedding model stays read-only, not a fifth selectable role**
 * (2026-08-22): every uploaded document's vector store
 * ([ch.arcticsoft.springchat3.document.DocumentIndex]) was embedded with
 * whatever model was configured at upload time. Swapping the embedding model
 * live would silently make new query embeddings incompatible with
 * already-stored document embeddings - not an error, just quietly wrong
 * similarity search results - until every document is re-uploaded. That is a
 * materially different (and worse) risk than swapping an LLM role, which only
 * affects the next call. It is also the one model that could never be a
 * *personal* choice, for the same reason: the index is shared.
 */
@RestController
class SettingsController(
    private val appSettingsStore: AppSettingsStore,
    private val userSettingsStore: UserSettingsStore,
    private val settingsResolver: SettingsResolver,
    private val spaceAccess: SpaceAccess,
    private val admins: Admins,
    private val ollamaApi: OllamaApi,
    @Value("\${spring.ai.ollama.embedding.model}") private val embeddingModel: String,
) {
    private val log = LoggerFactory.getLogger(SettingsController::class.java)

    /**
     * Every model tag currently pulled in the local Ollama installation
     * ([OllamaApi.listModels], `GET /api/tags` under the hood - the same call
     * embabel-agent-ollama-autoconfigure's own `OllamaModelsConfig` makes at
     * startup to auto-register each one as an LLM). Empty (not an error) if
     * Ollama isn't reachable right now - the popup just shows empty dropdowns
     * rather than failing the whole settings view.
     */
    private fun installedModels(): List<String> =
        try {
            ollamaApi.listModels().models().map { it.name() }.sorted()
        } catch (e: Exception) {
            log.warn("Could not list Ollama models for the settings popup - dropdowns will be empty", e)
            emptyList()
        }

    private fun response(email: String): SettingsResponse {
        val installed = installedModels()
        return SettingsResponse(
            models = SettingsModels(
                toolSelection = settingsResolver.activeModel(email, ModelRoleKeys.TOOL_SELECTION),
                documentSearchStrategy = settingsResolver.activeModel(email, ModelRoleKeys.DOCUMENT_SEARCH_STRATEGY),
                documentEdit = settingsResolver.activeModel(email, ModelRoleKeys.DOCUMENT_EDIT),
                generation = settingsResolver.activeModel(email, ModelRoleKeys.GENERATION),
            ),
            embeddingModel = embeddingModel,
            availableModels = settingsResolver.selectableModels(installed),
            installedModels = installed,
            allowedModels = appSettingsStore.get().allowedModels,
            toolsEnabled = settingsResolver.toolsEnabledFor(email),
            documentEditingEnabled = appSettingsStore.get().documentEditingEnabled,
            admin = admins.isAdmin(email),
        )
    }

    @GetMapping("/settings")
    fun settings(exchange: ServerWebExchange): SettingsResponse =
        response(spaceAccess.currentUserEmail(exchange))

    /** The caller's own tool-use choice. Not admin-gated: it changes nothing outside their own turns. */
    @PostMapping("/settings/tools")
    fun setToolsEnabled(@RequestBody request: ToolsEnabledRequest, exchange: ServerWebExchange): SettingsResponse {
        val email = spaceAccess.currentUserEmail(exchange)
        userSettingsStore.setToolsEnabled(email, request.enabled)
        return response(email)
    }

    /**
     * Server policy, so admin-only (2026-08-25). Separate from
     * [setToolsEnabled] for the older reason too - see
     * [ch.arcticsoft.springchat3.settings.AppSettings.documentEditingEnabled]
     * for why "may the agent look things up" and "may the agent change my
     * documents" are two questions. It stays global because a permission a
     * user grants themselves is not a permission; a *viewer* is still blocked
     * on top of this by their space role, in ChatController.
     */
    @PostMapping("/settings/document-editing")
    fun setDocumentEditingEnabled(@RequestBody request: ToolsEnabledRequest, exchange: ServerWebExchange): SettingsResponse {
        val email = spaceAccess.currentUserEmail(exchange)
        admins.requireAdmin(email)
        appSettingsStore.setDocumentEditingEnabled(request.enabled)
        return response(email)
    }

    /**
     * The caller's own model choice for one role. A tag the allow-list
     * forbids is rejected here rather than quietly ignored at resolution
     * time: the dropdown should never have offered it, so a request carrying
     * one is a stale page or a hand-made call, and both are better told.
     */
    @PostMapping("/settings/model")
    fun setModelOverride(@RequestBody request: ModelOverrideRequest, exchange: ServerWebExchange): SettingsResponse {
        val email = spaceAccess.currentUserEmail(exchange)
        val model = request.model
        if (!model.isNullOrBlank() && !settingsResolver.isAllowed(model)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "That model is not one this server allows")
        }
        userSettingsStore.setModelOverride(email, request.role, model)
        return response(email)
    }

    /**
     * Replaces the curated allow-list - admin-only (2026-08-25, user's own
     * request, to stop several people each pinning their own large model on
     * one shared Ollama host). Sending an empty list means "no curation".
     */
    @PostMapping("/settings/allowed-models")
    fun setAllowedModels(@RequestBody request: AllowedModelsRequest, exchange: ServerWebExchange): SettingsResponse {
        val email = spaceAccess.currentUserEmail(exchange)
        admins.requireAdmin(email)
        appSettingsStore.setAllowedModels(request.models)
        return response(email)
    }
}

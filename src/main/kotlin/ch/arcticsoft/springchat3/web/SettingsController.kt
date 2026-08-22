package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.settings.AppSettingsStore
import ch.arcticsoft.springchat3.settings.ModelRoleKeys
import org.slf4j.LoggerFactory
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * The three selectable roles' *currently active* model - the user's override
 * from `POST /settings/model` if one is set for that role
 * ([AppSettingsStore]), otherwise whatever `application.yml` configures. Not
 * the configured default itself - see [SettingsController.activeModel].
 */
data class SettingsModels(
    val toolSelection: String,
    val documentSearchStrategy: String,
    val generation: String,
)

/** [SettingsController]'s response shape for `GET /settings` and both `POST` endpoints. */
data class SettingsResponse(
    val models: SettingsModels,
    val embeddingModel: String,
    val availableModels: List<String>,
    val toolsEnabled: Boolean,
)

/** `POST /settings/tools`'s request body. */
data class ToolsEnabledRequest(val enabled: Boolean)

/**
 * `POST /settings/model`'s request body - [role] is one of [ModelRoleKeys]'s
 * constants, [model] the exact Ollama tag to use for it from now on, or
 * null/blank to clear the override and revert to that role's configured
 * default.
 */
data class ModelOverrideRequest(val role: String, val model: String?)

/**
 * Backs index.html's settings popup (2026-08-22, see
 * springchat3_settings.md in project memory): shows the three selectable
 * roles' currently active models plus the read-only embedding model, the
 * full list of models actually available in the local Ollama installation
 * (so the popup's dropdowns only ever offer real choices), and the
 * tool-use toggle.
 *
 * **Why the embedding model stays read-only, not a fourth selectable role**
 * (2026-08-22, same day the model-selection feature was added): every
 * uploaded document's vector store ([ch.arcticsoft.springchat3.document.DocumentIndex])
 * was embedded with whatever model was configured at upload time. Swapping
 * the embedding model live would silently make new query embeddings
 * incompatible with already-stored document embeddings - not an error, just
 * quietly wrong similarity search results - until every document is
 * re-uploaded. That's a materially different (and worse) risk than swapping
 * an LLM role, which only affects the next call, so this was deliberately
 * scoped out rather than exposed with a big warning label.
 */
@RestController
class SettingsController(
    private val appSettingsStore: AppSettingsStore,
    private val ollamaApi: OllamaApi,
    @Value("\${embabel.models.default-llm}") private val toolSelectionDefault: String,
    @Value("\${embabel.models.llms.generation}") private val generationDefault: String,
    @Value("\${embabel.models.llms.document-search-strategy}") private val documentSearchStrategyDefault: String,
    @Value("\${spring.ai.ollama.embedding.model}") private val embeddingModel: String,
) {
    private val log = LoggerFactory.getLogger(SettingsController::class.java)

    private fun activeModel(role: String, default: String): String =
        appSettingsStore.get().modelOverrides[role] ?: default

    /**
     * Every model tag currently pulled in the local Ollama installation
     * ([OllamaApi.listModels], `GET /api/tags` under the hood - same call
     * embabel-agent-ollama-autoconfigure's own `OllamaModelsConfig` makes at
     * startup to auto-register each one as an LLM). Empty (not an error) if
     * Ollama isn't reachable right now - the popup just shows empty
     * dropdowns rather than failing the whole settings view.
     */
    private fun availableModels(): List<String> =
        try {
            ollamaApi.listModels().models().map { it.name() }.sorted()
        } catch (e: Exception) {
            log.warn("Could not list Ollama models for the settings popup - dropdowns will be empty", e)
            emptyList()
        }

    private fun response(): SettingsResponse = SettingsResponse(
        models = SettingsModels(
            toolSelection = activeModel(ModelRoleKeys.TOOL_SELECTION, toolSelectionDefault),
            documentSearchStrategy = activeModel(ModelRoleKeys.DOCUMENT_SEARCH_STRATEGY, documentSearchStrategyDefault),
            generation = activeModel(ModelRoleKeys.GENERATION, generationDefault),
        ),
        embeddingModel = embeddingModel,
        availableModels = availableModels(),
        toolsEnabled = appSettingsStore.get().toolsEnabled,
    )

    @GetMapping("/settings")
    fun settings(): SettingsResponse = response()

    @PostMapping("/settings/tools")
    fun setToolsEnabled(@RequestBody request: ToolsEnabledRequest): SettingsResponse {
        appSettingsStore.setToolsEnabled(request.enabled)
        return response()
    }

    @PostMapping("/settings/model")
    fun setModelOverride(@RequestBody request: ModelOverrideRequest): SettingsResponse {
        appSettingsStore.setModelOverride(request.role, request.model)
        return response()
    }
}

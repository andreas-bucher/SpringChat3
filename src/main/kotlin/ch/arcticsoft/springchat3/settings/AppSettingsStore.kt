package ch.arcticsoft.springchat3.settings

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/**
 * App-wide settings the user can change from index.html's settings popup
 * (2026-08-22, see springchat3_settings.md in project memory) - [toolsEnabled]
 * plus, since the same day, [modelOverrides] - a single object rather than
 * separate top-level values so a later setting can be added here without
 * another persistence-format change.
 *
 * Deliberately global, not per-conversation/per-request - this is a
 * single-user local app (see springchat3_document_qa.md's own reasoning for
 * why per-document scoping was needed there but not everywhere), so "a
 * setting" means one value for the whole running app, not something threaded
 * through [ch.arcticsoft.springchat3.agent.ChatRequest] the way
 * `documentId`/`latitude`/`longitude` are. A plain curl caller of `/chat`
 * gets the same tool-use behavior and model choices the browser UI just set,
 * without needing to know about new request fields.
 *
 * [modelOverrides] maps a [ModelRoleKeys] key to the exact Ollama tag the
 * user picked in the settings popup, in place of that role's
 * `application.yml`-configured default - see
 * [ch.arcticsoft.springchat3.agent.ChatAgent]'s `toolSelectionLlm`/`llmForRole`
 * helpers for how this is actually applied. A role absent from this map (the
 * common case - nothing overridden) just uses its configured default, same
 * as before this feature existed. Deliberately NOT extended to the embedding
 * model - see [ch.arcticsoft.springchat3.web.SettingsController]'s doc
 * comment for why that one stays read-only.
 */
data class AppSettings(
    // Defaults to disabled (2026-08-22, was `true` - user's own request "make
    // Tool use by default disabled" - see springchat3_settings.md in project
    // memory). Only affects a fresh install with no settings.json yet, or a
    // load failure (see [loadPersisted] below) - an existing installation
    // that already persisted `toolsEnabled: true` keeps that value until the
    // user flips the settings-popup toggle off themselves.
    val toolsEnabled: Boolean = false,
    // Whether the agent may CHANGE a document, as opposed to reading one
    // (2026-08-23, the documentEdit action - see ChatAgent). Its own flag
    // rather than riding on [toolsEnabled]: that one gates "may the agent
    // look things up", which is a different question from "may the agent
    // write to my files", and someone who turns lookups off has not thereby
    // said anything about editing. Defaults to disabled, same reasoning
    // [toolsEnabled] itself defaults off - a capability with side effects
    // should be opted into, not discovered.
    val documentEditingEnabled: Boolean = false,
    val modelOverrides: Map<String, String> = emptyMap(),
)

/**
 * Keys into [AppSettings.modelOverrides] and the `role` field of
 * `POST /settings/model`'s request body (2026-08-22, see
 * springchat3_settings.md in project memory). [GENERATION] and
 * [DOCUMENT_SEARCH_STRATEGY] are also real Embabel role names - the exact
 * strings [ch.arcticsoft.springchat3.agent.ChatAgent] already passes to
 * `Ai.withLlmByRole(...)`, matching `embabel.models.llms.*` in
 * application.yml - reused as-is here rather than inventing a second naming
 * scheme for the same thing. [TOOL_SELECTION] has no Embabel role
 * counterpart: tool selection uses Embabel's `default-llm` via
 * `Ai.withDefaultLlm()`, not a named role, so this key exists purely for
 * this app's own override map and settings UI.
 */
object ModelRoleKeys {
    const val TOOL_SELECTION = "tool-selection"
    const val DOCUMENT_SEARCH_STRATEGY = "document-search-strategy"
    const val DOCUMENT_EDIT = "document-edit"
    const val GENERATION = "generation"
}

/**
 * Persists [AppSettings] to `[data-dir]/settings.json` - same per-file JSON
 * persistence pattern as
 * [ch.arcticsoft.springchat3.document.DocumentStore]/[ch.arcticsoft.springchat3.document.DocumentStructureStore],
 * but one shared top-level file rather than one per document, since there's
 * exactly one of these for the whole app rather than one per upload. Loaded
 * eagerly at construction (like [ch.arcticsoft.springchat3.document.DocumentStore],
 * not lazily like [ch.arcticsoft.springchat3.document.DocumentStructureStore]'s
 * cache) - there's only ever one value to load, so there's no per-key warm-up
 * cost worth deferring.
 *
 * Read directly by [ch.arcticsoft.springchat3.agent.ChatAgent] (not routed
 * through [ch.arcticsoft.springchat3.agent.ChatRequest]) and exposed to the
 * UI via [ch.arcticsoft.springchat3.web.SettingsController]. Important
 * caveat this app deliberately does NOT try to work around: Embabel binds
 * `embabel.models.*` once at Spring context startup
 * (`ConfigurableModelProviderProperties`, a plain `@ConfigurationProperties`
 * with no `@RefreshScope`) - so [modelOverrides] is entirely this app's own
 * runtime layer on top, applied per-call via `Ai.withLlm(exactModelName)`
 * rather than by trying to mutate Embabel's own static role→model binding,
 * which isn't something Embabel supports changing after startup anyway.
 */
@Component
class AppSettingsStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
) {
    private val log = LoggerFactory.getLogger(AppSettingsStore::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var settings: AppSettings = loadPersisted()

    private fun settingsFile() = File(dataDir, "settings.json")

    private fun loadPersisted(): AppSettings {
        val file = settingsFile()
        if (!file.exists()) return AppSettings()
        return try {
            objectMapper.readValue<AppSettings>(file)
        } catch (e: Exception) {
            log.warn("Could not load persisted settings from {} - defaulting to {}", file, AppSettings(), e)
            AppSettings()
        }
    }

    private fun persist(updated: AppSettings): AppSettings {
        settings = updated
        try {
            val file = settingsFile()
            file.parentFile.mkdirs()
            objectMapper.writeValue(file, updated)
        } catch (e: Exception) {
            log.warn("Could not persist settings ({}) to {}", updated, settingsFile(), e)
        }
        return updated
    }

    /** Current settings - always available, never null (defaults if nothing was ever saved or the file failed to load). */
    fun get(): AppSettings = settings

    /** Updates and persists [AppSettings.toolsEnabled], returning the resulting settings. */
    fun setToolsEnabled(enabled: Boolean): AppSettings = persist(settings.copy(toolsEnabled = enabled))

    /** Updates and persists [AppSettings.documentEditingEnabled], returning the resulting settings. */
    fun setDocumentEditingEnabled(enabled: Boolean): AppSettings = persist(settings.copy(documentEditingEnabled = enabled))

    /**
     * Sets [role]'s model override to [model] (a real Ollama tag, e.g.
     * "llama3.2:1b"), or clears it - reverting that role to its
     * `application.yml`-configured default - when [model] is null or blank.
     * [role] is one of [ModelRoleKeys]'s constants; an unrecognized role is
     * harmlessly stored and simply never looked up by anything.
     */
    fun setModelOverride(role: String, model: String?): AppSettings {
        val overrides = if (model.isNullOrBlank()) {
            settings.modelOverrides - role
        } else {
            settings.modelOverrides + (role to model)
        }
        return persist(settings.copy(modelOverrides = overrides))
    }
}

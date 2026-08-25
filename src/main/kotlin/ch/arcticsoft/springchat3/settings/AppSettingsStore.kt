package ch.arcticsoft.springchat3.settings

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/**
 * Server-wide settings (2026-08-22, see springchat3_settings.md in project
 * memory) - one object rather than separate top-level values so a later
 * setting can be added here without another persistence-format change.
 *
 * **Not all of these mean the same thing any more (2026-08-25).** This class
 * was written when the app had one user; since it grew accounts and shared
 * spaces, "a setting" splits in two, and this file now holds both halves:
 *
 *  - **Policy**, which one person decides for the whole server:
 *    [documentEditingEnabled] and [allowedModels]. Both have effects beyond
 *    the person flipping them - the agent writes into documents other people
 *    can see, and every model runs on one shared Ollama host - so they are
 *    admin-only ([ch.arcticsoft.springchat3.security.Admins]).
 *  - **Defaults**, which every user simply inherits until they choose
 *    otherwise: [toolsEnabled] and [modelOverrides]. The per-user choice
 *    lives in [UserSettingsStore]; this is what a user who has never opened
 *    the settings popup gets. Keeping them here rather than deleting them is
 *    what makes the split migration-free: an existing `settings.json` keeps
 *    behaving exactly as it did, for everyone, until someone picks their own.
 *
 * Nothing reads this store to answer "what applies to this caller" - that is
 * [SettingsResolver]'s single job, and both the agent and the settings UI go
 * through it. A plain curl caller of `/chat` still gets a server-resolved
 * answer rather than having to know about new request fields; it is now
 * resolved from *their* identity rather than from one global value.
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
    // user flips the settings-popup toggle off themselves. Since 2026-08-25
    // this is the value a user inherits, not the value that applies: see
    // [UserSettings.toolsEnabled].
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
    /**
     * The model tags a user is allowed to pick in the settings popup, or
     * empty for "every model the local Ollama has" (2026-08-25, user's own
     * request: "we could make it global configurable which models we allow,
     * thereby we could ensure we only active some models and not all ollama
     * has available"). Empty is the default precisely so an existing
     * installation behaves exactly as before - same no-migration rule the
     * ownership legacy cases use (see
     * [ch.arcticsoft.springchat3.project.SpaceAccess]).
     *
     * The point is one shared Ollama host: three people each pinning their
     * own 8B+ model thrash VRAM in a way a single shared choice never did.
     * Enforced in [SettingsResolver], **not** by only filtering the
     * dropdown - a curated list that lives in the browser is decoration, the
     * same way client-side space filtering was not enforcement.
     */
    val allowedModels: List<String> = emptyList(),
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

    // Synchronized since 2026-08-25: read-copy-write on a shared object was
    // safe while one person could be changing settings; with accounts, two
    // admins saving at once could drop one of the two changes.
    @Synchronized
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

    /**
     * Replaces the curated model allow-list ([AppSettings.allowedModels]).
     * Blank entries are dropped and duplicates collapsed; an empty result is
     * stored as empty, which means "no curation - offer everything Ollama
     * has" rather than "offer nothing". A tag that isn't currently pulled is
     * kept rather than rejected: the list is a policy about names, and a
     * model can be pulled again later.
     */
    fun setAllowedModels(models: List<String>): AppSettings =
        persist(settings.copy(allowedModels = models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()))
}

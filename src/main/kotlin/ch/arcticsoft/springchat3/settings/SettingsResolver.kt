package ch.arcticsoft.springchat3.settings

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * The one place that answers "which settings apply to this caller"
 * (2026-08-25, the per-user/global split - see springchat3_settings.md in
 * project memory).
 *
 * Centralised for the same reason [ch.arcticsoft.springchat3.project.SpaceAccess]
 * was: the settings popup and the agent both need this answer, and if each
 * worked it out for itself they would drift - a user would see one model
 * named in the popup and a different one in the turn's trace, which reads as
 * the feature being broken rather than as two code paths disagreeing.
 *
 * Resolution order, per role: **the user's own choice, then the server
 * default, then `application.yml`** - with any tag the curated allow-list
 * forbids dropped at whichever layer it appears, so a stale personal
 * override falls back rather than escaping the policy. A role with nothing
 * set anywhere is simply absent from the resolved map, which is what lets
 * [ch.arcticsoft.springchat3.agent.ChatAgent] keep its original
 * `withLlmByRole`/`withDefaultLlm` fallback instead of always being handed an
 * exact tag - naming the model directly bypasses Embabel's own role
 * indirection, and doing that on the *default* path would be a silent change
 * in behaviour for installations that never picked anything.
 */
@Component
class SettingsResolver(
    private val appSettingsStore: AppSettingsStore,
    private val userSettingsStore: UserSettingsStore,
    @Value("\${embabel.models.default-llm}") private val toolSelectionDefault: String,
    @Value("\${embabel.models.llms.generation}") private val generationDefault: String,
    @Value("\${embabel.models.llms.document-search-strategy}") private val documentSearchStrategyDefault: String,
    @Value("\${embabel.models.llms.document-edit}") private val documentEditDefault: String,
) {
    /** The `application.yml`-configured model for [role], or null for a role this app does not know. */
    fun defaultFor(role: String): String? = when (role) {
        ModelRoleKeys.TOOL_SELECTION -> toolSelectionDefault
        ModelRoleKeys.GENERATION -> generationDefault
        ModelRoleKeys.DOCUMENT_SEARCH_STRATEGY -> documentSearchStrategyDefault
        ModelRoleKeys.DOCUMENT_EDIT -> documentEditDefault
        else -> null
    }

    private fun configuredDefaults(): List<String> = listOf(
        toolSelectionDefault,
        generationDefault,
        documentSearchStrategyDefault,
        documentEditDefault,
    ).distinct()

    /**
     * Whether [model] may be used at all. An empty
     * [AppSettings.allowedModels] means no curation, so everything passes.
     *
     * A configured default always passes even when it is not on the list:
     * otherwise one typo in the admin UI takes every role down to a model
     * nobody selected, which is a far worse failure than an over-broad list.
     */
    fun isAllowed(model: String): Boolean {
        val allowed = appSettingsStore.get().allowedModels
        return allowed.isEmpty() || model in allowed || model in configuredDefaults()
    }

    /**
     * What the popup's dropdowns may offer, given everything [installed] in
     * the local Ollama. The configured defaults are always included so a role
     * can always be reset to what `application.yml` says, even if the admin
     * curated them out or that model is not pulled on this host right now.
     */
    fun selectableModels(installed: List<String>): List<String> =
        (installed.filter { isAllowed(it) } + configuredDefaults()).distinct().sorted()

    /** Whether the agent may use tools for [email]'s turn - their own choice if they made one, else the server default. */
    fun toolsEnabledFor(email: String): Boolean =
        userSettingsStore.get(email).toolsEnabled ?: appSettingsStore.get().toolsEnabled

    /**
     * [email]'s effective role-to-model overrides: the server's own overrides
     * with the user's laid on top, both filtered through [isAllowed] first so
     * that curating a model away really stops it being used rather than just
     * hiding it in the dropdown. Sparse by design - see this class's doc
     * comment for why an unset role must stay unset.
     */
    fun effectiveOverrides(email: String): Map<String, String> {
        val server = appSettingsStore.get().modelOverrides.filterValues { isAllowed(it) }
        val own = userSettingsStore.get(email).modelOverrides.filterValues { isAllowed(it) }
        return server + own
    }

    /** The exact model tag [role] will actually use for [email]'s next turn. */
    fun activeModel(email: String, role: String): String =
        effectiveOverrides(email)[role] ?: defaultFor(role).orEmpty()
}

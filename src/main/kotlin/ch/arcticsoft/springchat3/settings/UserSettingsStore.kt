package ch.arcticsoft.springchat3.settings

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/**
 * One person's own settings-popup choices (2026-08-25, user's own question
 * "would it not make more sense to make the settings per user ... each user
 * can set what for him is appropriate" - see springchat3_settings.md in
 * project memory).
 *
 * Only the two settings that affect nothing but the asker's own turn live
 * here. [AppSettings.documentEditingEnabled] deliberately does not: a
 * permission a user grants themselves is not a permission, and the agent's
 * edits land in documents other people can see.
 *
 * Every field means "not chosen" when it is null/empty, in which case the
 * matching value from [AppSettings] applies. That is what keeps this
 * migration-free - a user with no entry here behaves exactly as the whole app
 * behaved before the split.
 *
 * `ignoreUnknown` because the first build of this class had a `fun isEmpty()`,
 * which Jackson read as a property and wrote into every entry; a file saved by
 * that build must still load rather than throwing away everyone's choices.
 * That is also why [hasNothingSet] is not named `isEmpty` - Jackson treats a
 * no-arg `is`/`get` method as a getter, and a computed one round-trips into
 * the file as a field nothing can read back.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserSettings(
    /** Null means "inherit [AppSettings.toolsEnabled]" - NOT "off". */
    val toolsEnabled: Boolean? = null,
    /** Sparse: a role absent here falls back to [AppSettings.modelOverrides], then to the configured default. */
    val modelOverrides: Map<String, String> = emptyMap(),
) {
    /** True when this carries no choice at all, so the entry can be dropped rather than stored as noise. Deliberately NOT named `isEmpty` - see the class doc. */
    fun hasNothingSet(): Boolean = toolsEnabled == null && modelOverrides.isEmpty()
}

/**
 * Persists every user's [UserSettings] to `[data-dir]/user-settings.json` as
 * one `email -> settings` object.
 *
 * One file rather than one per user, unlike
 * [ch.arcticsoft.springchat3.project.ProjectStore]'s per-space folders: an
 * email is not a safe file name (case, dots, `+` tags, non-ASCII), and
 * slugging it would invent a second identity for a user whose identity this
 * app has deliberately kept as one lowercased string everywhere else. The
 * roster is small - it is bounded by the sign-in allow-list, not by usage.
 *
 * Keys are lowercased on both read and write, the same normalisation
 * [ch.arcticsoft.springchat3.security.CurrentUserWebFilter] applies when it
 * puts the caller's email on the exchange, so two spellings of one address
 * can never end up with two sets of preferences.
 *
 * Nothing outside [SettingsResolver] should read this directly - "what
 * applies to this caller" is a question with one answer, in one place.
 */
@Component
class UserSettingsStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
) {
    private val log = LoggerFactory.getLogger(UserSettingsStore::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var byEmail: Map<String, UserSettings> = loadPersisted()

    private fun storeFile() = File(dataDir, "user-settings.json")

    private fun loadPersisted(): Map<String, UserSettings> {
        val file = storeFile()
        if (!file.exists()) return emptyMap()
        return try {
            objectMapper.readValue<Map<String, UserSettings>>(file)
                .mapKeys { (email, _) -> email.lowercase() }
        } catch (e: Exception) {
            log.warn("Could not load per-user settings from {} - every user falls back to the server defaults", file, e)
            emptyMap()
        }
    }

    // Synchronized, unlike the single-value store this grew out of: two
    // different people saving their own preferences at the same moment is
    // the normal case here, not a rare one, and a plain read-copy-write on
    // the shared map would lose one of them.
    @Synchronized
    private fun persist(email: String, updated: UserSettings): UserSettings {
        val key = email.lowercase()
        byEmail = if (updated.hasNothingSet()) byEmail - key else byEmail + (key to updated)
        try {
            val file = storeFile()
            file.parentFile.mkdirs()
            objectMapper.writeValue(file, byEmail)
        } catch (e: Exception) {
            log.warn("Could not persist per-user settings for {} to {}", key, storeFile(), e)
        }
        return updated
    }

    /** [email]'s own choices - all-null/empty (i.e. "inherit everything") if they have never changed a setting. */
    fun get(email: String): UserSettings = byEmail[email.lowercase()] ?: UserSettings()

    /** Sets [email]'s own tool-use choice, or clears it - reverting them to the server default - when [enabled] is null. */
    fun setToolsEnabled(email: String, enabled: Boolean?): UserSettings =
        persist(email, get(email).copy(toolsEnabled = enabled))

    /**
     * Sets [email]'s own model for [role], or clears it - falling back to the
     * server default for that role - when [model] is null or blank. Same
     * clear-by-blank convention as [AppSettingsStore.setModelOverride], so
     * the popup can keep using one empty-value option for both.
     */
    fun setModelOverride(email: String, role: String, model: String?): UserSettings {
        val current = get(email)
        val overrides = if (model.isNullOrBlank()) {
            current.modelOverrides - role
        } else {
            current.modelOverrides + (role to model)
        }
        return persist(email, current.copy(modelOverrides = overrides))
    }
}

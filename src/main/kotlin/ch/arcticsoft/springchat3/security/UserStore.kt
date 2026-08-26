package ch.arcticsoft.springchat3.security

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.io.File

/**
 * One account that signs in with a password rather than through Google
 * (2026-08-24, user's own question "How to support sign in of users not
 * having a Google Account" - see springchat3_local_accounts.md in project
 * memory).
 *
 * [email] is the identity, lowercased, and is deliberately the *same* key a
 * Google sign-in produces: everything downstream
 * ([ch.arcticsoft.springchat3.project.SpaceAccess], space membership,
 * chat-session ownership) works off that one string and neither knows nor
 * cares how it was proved. A local account and a Google account with the
 * same address are therefore the same person by design - worth knowing in
 * both directions, since it also means whoever controls that Google address
 * can reach a local account named after it.
 *
 * [password] is the **admin's plaintext bootstrap field** and is normally
 * absent. The admin adds an account by writing one into `users.json` by
 * hand; the store hashes it into [passwordHash], clears it, and rewrites the
 * file the first time it reads it (see [UserStore]). It exists only so
 * that adding a user needs no separate hashing tool and no UI - the tradeoff
 * being that the plaintext does sit in that file until the app next reads
 * it, which is why [UserStore] rewrites eagerly rather than lazily.
 *
 * [mustChangePassword] defaults to true for exactly that reason: a password
 * the admin chose is a password the admin knows.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AppUser(
    val email: String,
    val displayName: String = "",
    val password: String? = null,
    val passwordHash: String? = null,
    val mustChangePassword: Boolean = true,
    val disabled: Boolean = false,
    val createdAt: Long = 0,
)

/**
 * Every local (non-Google) account, in `[data-dir]/users.json` - the same
 * write-through, single-shared-file JSON pattern
 * [ch.arcticsoft.springchat3.settings.AppSettingsStore] uses, and
 * deliberately NOT one of the per-space files
 * [ch.arcticsoft.springchat3.document.SpaceScopedJsonStore] handles: an
 * account exists before and independently of any space. `data/` is
 * gitignored, so no password hash reaches the repository.
 *
 * **Adding a user, the whole procedure:** put an object in `users.json` with
 * an `email`, a `displayName` and a plaintext `password`. The store hashes
 * it on its next read and rewrites the file with `passwordHash` only. No
 * restart needed - [reloadIfChanged] re-reads whenever the file's
 * modification time moves, so the account works from the next sign-in
 * attempt onward.
 *
 * ```json
 * [ { "email": "someone@example.com", "displayName": "Someone", "password": "choose-something" } ]
 * ```
 *
 * A corrupt file logs a warning and yields no accounts, rather than failing
 * application startup - same contract every other store in this app has.
 * Note what that means here, and that it is the right way round: a broken
 * `users.json` locks local users out, it never lets anyone in.
 */
@Component
class UserStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
    private val passwordEncoder: PasswordEncoder,
) {
    private val log = LoggerFactory.getLogger(UserStore::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var users: List<AppUser> = emptyList()

    @Volatile
    private var loadedFromModifiedAt: Long = -1

    private fun storeFile() = File(dataDir, "users.json")

    /**
     * One account by email, case-insensitively, or null if there is none -
     * the only lookup [SecurityConfig]'s `ReactiveUserDetailsService` needs.
     */
    fun find(email: String): AppUser? {
        reloadIfChanged()
        return users.firstOrNull { it.email.equals(email, ignoreCase = true) }
    }

    /** Every local account - for `GET /me` to tell a local session from a Google one. */
    fun list(): List<AppUser> {
        reloadIfChanged()
        return users
    }

    /**
     * Replaces [email]'s stored password with a freshly hashed [rawPassword]
     * and clears [AppUser.mustChangePassword] - the one write path
     * [ch.arcticsoft.springchat3.web.AccountController] uses. Returns false
     * if there is no such account.
     */
    @Synchronized
    fun changePassword(email: String, rawPassword: String): Boolean {
        reloadIfChanged()
        val existing = users.firstOrNull { it.email.equals(email, ignoreCase = true) } ?: return false
        users = users.map {
            if (it.email.equals(email, ignoreCase = true)) {
                it.copy(password = null, passwordHash = passwordEncoder.encode(rawPassword), mustChangePassword = false)
            } else {
                it
            }
        }
        log.info("Password changed for local account {}", existing.email)
        persist()
        return true
    }

    /**
     * Re-reads `users.json` when its modification time has moved since the
     * last read, so an account added by hand works without restarting the
     * app. Cheap enough to do per lookup (one `lastModified` syscall on a
     * file with a handful of entries), and the alternative - caching for the
     * process's lifetime - would make "add a user" mean "restart the
     * server", which is exactly the friction this design is trying to avoid.
     */
    @Synchronized
    private fun reloadIfChanged() {
        val file = storeFile()
        val modifiedAt = if (file.exists()) file.lastModified() else 0
        if (modifiedAt == loadedFromModifiedAt) return
        loadedFromModifiedAt = modifiedAt
        users = load(file)
        if (users.any { it.password != null }) {
            hashPlaintextPasswords()
        }
    }

    private fun load(file: File): List<AppUser> {
        if (!file.exists()) return emptyList()
        return try {
            objectMapper.readValue<List<AppUser>>(file)
                .filter { it.email.isNotBlank() }
                .map { it.copy(email = it.email.lowercase()) }
        } catch (e: Exception) {
            log.warn("Could not load local accounts from {} - no local sign-in will be possible", file, e)
            emptyList()
        }
    }

    /**
     * Turns every plaintext [AppUser.password] the admin wrote into a
     * [AppUser.passwordHash] and rewrites the file. An entry that has both
     * keeps the plaintext one: writing a new password by hand is how an
     * admin resets a forgotten one, and that has to win over the hash that
     * is already there or the reset would silently do nothing.
     */
    private fun hashPlaintextPasswords() {
        users = users.map { user ->
            val raw = user.password
            if (raw.isNullOrBlank()) {
                user
            } else {
                log.info("Hashing the bootstrap password for local account {}", user.email)
                user.copy(
                    password = null,
                    passwordHash = passwordEncoder.encode(raw),
                    createdAt = if (user.createdAt == 0L) System.currentTimeMillis() else user.createdAt,
                )
            }
        }
        persist()
    }

    private fun persist() {
        val file = storeFile()
        try {
            file.parentFile?.mkdirs()
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, users)
            // Recorded from the file this write just produced, so the next
            // reloadIfChanged sees its own work rather than treating it as
            // an outside edit and re-reading (and, worse, re-hashing).
            loadedFromModifiedAt = file.lastModified()
        } catch (e: Exception) {
            log.warn("Could not persist local accounts to {}", file, e)
        }
    }
}

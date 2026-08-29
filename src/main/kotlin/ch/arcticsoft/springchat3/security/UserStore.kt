package ch.arcticsoft.springchat3.security

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
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
 * the admin chose is a password the admin knows. It is meaningless on a row
 * with no password at all, and [UserStore] clears it on load rather than
 * leaving a Google-only row claiming it owes someone a password change.
 *
 * [google] is the other half (2026-08-26): whether this address may sign in
 * with Google. It used to be a separate roster entirely - the comma-separated
 * `SPRINGCHAT3_ALLOWED_EMAILS` - which meant the two ways of signing in were
 * administered in two places with two different latencies (`users.json`
 * re-reads itself, an env var needs a restart). Defaults to false, so every
 * row written before this existed keeps behaving exactly as it did.
 *
 * The three states are all meaningful and all reachable: `google` alone is a
 * Google account, a [passwordHash] alone is a password account, and both at
 * once is one person who can do either - which is the truth the old two-roster
 * design had to special-case in its picker.
 *
 * [admin] is the third roster to arrive here (2026-08-26, later the same day),
 * and the last one: it was `SPRINGCHAT3_ADMIN_EMAILS`, whose own doc comment
 * justified being an env var with "a Google account has no users.json row" -
 * which stopped being true a few hours earlier. It grants exactly two things:
 * changing server policy in the settings popup, and the `/admin/users` screen
 * that writes this file. Defaults to false, so no existing row becomes one by
 * accident.
 *
 * **Note what that second thing means.** An admin can edit the roster that
 * decides who may sign in, so a bug in one of those endpoints is not a wrong
 * setting - it is anyone getting in. That is why every route on
 * [ch.arcticsoft.springchat3.web.AdminUserController] gates before it reads
 * its body, and why this field is never writable through any other one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AppUser(
    val email: String,
    val displayName: String = "",
    val google: Boolean = false,
    val admin: Boolean = false,
    val password: String? = null,
    val passwordHash: String? = null,
    val mustChangePassword: Boolean = true,
    val disabled: Boolean = false,
    val createdAt: Long = 0,
) {
    @get:JsonIgnore
    val hasPassword: Boolean get() = !passwordHash.isNullOrBlank()

    /** What the share picker labels this row. Both rosters at once reads as Google - the sign-in page offers that button first. */
    @get:JsonIgnore
    val kind: String get() = if (google) "google" else "local"
}

/**
 * Every account that can sign in at all, in `[data-dir]/users.json` - the same
 * write-through, single-shared-file JSON pattern
 * [ch.arcticsoft.springchat3.settings.AppSettingsStore] uses, and
 * deliberately NOT one of the per-space files
 * [ch.arcticsoft.springchat3.document.SpaceScopedJsonStore] handles: an
 * account exists before and independently of any space. `data/` is
 * gitignored, so no password hash reaches the repository.
 *
 * **Both kinds of account live here (2026-08-26).** Google sign-in used to be
 * gated by its own roster, the `SPRINGCHAT3_ALLOWED_EMAILS` env var, joined
 * to this file by [KnownUsers]. The join was sound; the split was not. It
 * cost a server restart to let a colleague in, because an env var is parsed
 * once, while this file re-reads itself - so the two rosters behaved
 * differently for no reason a user could see. `springchat3.allowed-emails`
 * survives only as a one-time seed (see [seedUsers]).
 *
 * **Adding a user, the whole procedure:** put an object in `users.json` with
 * an `email`, a `displayName`, and then either `"google": true`, a plaintext
 * `password`, or both. A password is hashed on the next read and the file
 * rewritten with `passwordHash` only. No restart needed either way -
 * [reloadIfChanged] re-reads whenever the file's modification time moves, so
 * the account works from the next sign-in attempt onward.
 *
 * ```json
 * [
 *   { "email": "someone@example.com", "displayName": "Someone", "google": true },
 *   { "email": "other@example.com", "displayName": "Other", "password": "choose-something" }
 * ]
 * ```
 *
 * `disabled` now revokes a Google account too, which the env var had no way
 * to express short of an edit and a restart - and revoking is what you
 * actually want rather than deleting the row, since spaces are owned by
 * email and a deleted owner leaves spaces nothing can reach.
 *
 * A corrupt file logs a warning and yields no accounts, rather than failing
 * application startup - same contract every other store in this app has.
 * Note what that means here, and that it is the right way round: a broken
 * `users.json` locks everyone out, it never lets anyone in. That guarantee
 * got broader with the Google roster, not weaker.
 */
@Component
class UserStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
    @Value("\${springchat3.allowed-emails:}") private val seedGoogleEmailsRaw: String,
    @Value("\${springchat3.admin-emails:}") private val seedAdminEmailsRaw: String,
    private val passwordEncoder: PasswordEncoder,
) {
    private val log = LoggerFactory.getLogger(UserStore::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var users: List<AppUser> = emptyList()

    @Volatile
    private var loadedFromModifiedAt: Long = -1

    /**
     * Whether the one-shot Google seed has had its turn this run. Not a
     * cosmetic guard: without it the seed's condition would be read off
     * current state, and revoking the last Google account - the very thing
     * [AppUser.disabled] and `"google": false` exist for - would look
     * identical to a file that had never been seeded, so the next lookup
     * would put the roster straight back and write the revocation away.
     */
    @Volatile
    private var seedChecked = false

    private fun storeFile() = File(dataDir, "users.json")

    /**
     * Forces the first read at startup rather than at the first sign-in, so
     * that the seed and [warnAboutUnseededAddresses] land in the boot log -
     * where someone is looking - instead of in the middle of a request from
     * the person they concern. Same reason [Admins] warns from a
     * [PostConstruct] rather than lazily.
     */
    @PostConstruct
    fun loadAtStartup() {
        reloadIfChanged()
    }

    /**
     * One account by email, case-insensitively, or null if there is none -
     * the only lookup [SecurityConfig]'s `ReactiveUserDetailsService` needs.
     */
    fun find(email: String): AppUser? {
        reloadIfChanged()
        return users.firstOrNull { it.email.equals(email, ignoreCase = true) }
    }

    /** Every account, both kinds - the roster [KnownUsers] presents and `GET /me` reads a display name from. */
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
     * Adds an account (2026-08-26, the `/admin/users` screen - see
     * springchat3_admin_screen.md in project memory). [rawPassword] is hashed
     * here rather than written into the file's plaintext [AppUser.password]
     * field: that field exists for an admin with a text editor and no running
     * UI, and there is no reason to put a readable password on disk for even
     * the moment it would take the next read to pick it up.
     *
     * Returns null if the address already has a row. Callers turn that into a
     * conflict rather than merging - on the one screen that decides who may
     * sign in, "add" quietly becoming "overwrite" is not a mistake worth
     * making convenient.
     *
     * Every method below takes [by], the address of whoever asked, purely so
     * the log line has it. An env-var roster change is visible in deploy
     * history; a write from a web page is visible nowhere else.
     */
    @Synchronized
    fun create(email: String, displayName: String, google: Boolean, rawPassword: String?, admin: Boolean, by: String): AppUser? {
        reloadIfChanged()
        val normalized = email.trim().lowercase()
        if (users.any { it.email == normalized }) return null
        val hash = rawPassword?.takeIf { it.isNotBlank() }?.let { passwordEncoder.encode(it) }
        val user = AppUser(
            email = normalized,
            displayName = displayName.trim(),
            google = google,
            admin = admin,
            passwordHash = hash,
            // True for exactly the reason the file's bootstrap field sets it:
            // whoever typed this password into the admin screen knows it. Left
            // false when there is no password, so a Google-only row does not
            // claim it owes someone a password change.
            mustChangePassword = hash != null,
            createdAt = System.currentTimeMillis(),
        )
        users = users + user
        log.info("{} added account {} (google={}, password={}, admin={})", by, normalized, google, hash != null, admin)
        persist()
        return user
    }

    /**
     * Changes whichever of the four fields the caller passed - null means
     * "leave it". Returns the row as it now stands, or null if there is no
     * such account. **This method enforces nothing**: refusing to remove the
     * last administrator, to disable yourself, or to leave a row with no way
     * in is [ch.arcticsoft.springchat3.web.AdminUserController]'s job, in one
     * place, where the caller's own identity is known.
     */
    @Synchronized
    fun update(
        email: String,
        displayName: String? = null,
        google: Boolean? = null,
        admin: Boolean? = null,
        disabled: Boolean? = null,
        by: String,
    ): AppUser? {
        reloadIfChanged()
        val existing = users.firstOrNull { it.email.equals(email, ignoreCase = true) } ?: return null
        val updated = existing.copy(
            displayName = displayName?.trim() ?: existing.displayName,
            google = google ?: existing.google,
            admin = admin ?: existing.admin,
            disabled = disabled ?: existing.disabled,
        )
        if (updated == existing) return existing
        users = users.map { if (it.email == existing.email) updated else it }
        log.info("{} changed account {}: {}", by, existing.email, describeChange(existing, updated))
        persist()
        return updated
    }

    private fun describeChange(before: AppUser, after: AppUser): String =
        listOfNotNull(
            "displayName".takeIf { before.displayName != after.displayName },
            "google ${before.google} -> ${after.google}".takeIf { before.google != after.google },
            "admin ${before.admin} -> ${after.admin}".takeIf { before.admin != after.admin },
            "disabled ${before.disabled} -> ${after.disabled}".takeIf { before.disabled != after.disabled },
        ).joinToString(", ")

    /**
     * An administrator setting someone *else's* password. The mirror image of
     * [changePassword], and deliberately a separate method rather than a flag
     * on it: this one sets [AppUser.mustChangePassword] where that one clears
     * it, because a password its owner did not choose has to be replaced
     * before the session is good for anything (see [SessionGateWebFilter]).
     * Confusing the two would either trap a user who just chose their own
     * password or leave an admin-chosen one in place indefinitely.
     */
    @Synchronized
    fun setPassword(email: String, rawPassword: String, by: String): AppUser? {
        reloadIfChanged()
        val existing = users.firstOrNull { it.email.equals(email, ignoreCase = true) } ?: return null
        val updated = existing.copy(
            password = null,
            passwordHash = passwordEncoder.encode(rawPassword),
            mustChangePassword = true,
        )
        users = users.map { if (it.email == existing.email) updated else it }
        log.info("{} reset the password for account {}", by, existing.email)
        persist()
        return updated
    }

    /**
     * Removes the row outright. [AppUser.disabled] is the better answer
     * almost every time - spaces are owned by an email string and space
     * deletion is owner-only, so deleting the owner of one leaves it with an
     * owner nobody can ever sign in as. The controller refuses this for
     * anyone who still owns a space, which is where that rule is enforced.
     */
    @Synchronized
    fun delete(email: String, by: String): Boolean {
        reloadIfChanged()
        val existing = users.firstOrNull { it.email.equals(email, ignoreCase = true) } ?: return false
        users = users.filterNot { it.email == existing.email }
        log.warn("{} deleted account {}", by, existing.email)
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
        // One existence check, reused. Asking twice would let the file appear
        // or vanish between the two answers, and the second answer is what
        // decides whether to seed - i.e. whether to write.
        val exists = file.exists()
        val modifiedAt = if (exists) file.lastModified() else 0
        if (modifiedAt == loadedFromModifiedAt) return
        loadedFromModifiedAt = modifiedAt
        val loaded = load(file)
        users = loaded ?: emptyList()
        // Spent on the first read of the run whatever that read found, so the
        // seed cannot stay armed. Only a genuinely absent file gets seeded:
        // "no Google rows yet" is a different question and must not be asked
        // here, because a file loses its last Google row when someone revokes
        // it, and re-seeding would write the revocation away. A file that
        // merely vanished for a moment mid-run - an editor's write-temp-then-
        // rename, a sync client, a restored backup - is past this point.
        if (!seedChecked) {
            seedChecked = true
            if (!exists) seedUsers() else if (loaded != null) warnAboutUnseededAddresses()
            // Not inside either branch: "there is no administrator" is worth
            // saying however the roster got to that state. Skipped only for a
            // file that would not parse, where it would be a second warning
            // about the same one problem.
            if (loaded != null || !exists) warnIfNoAdmin()
        }
        // A file that would not parse is left exactly as it is - no accounts
        // this run, but nothing written over the top of it. Rewriting an
        // unreadable file would turn a typo in a single entry into the
        // permanent loss of every other.
        if (loaded == null) return
        if (users.any { it.password != null }) {
            hashPlaintextPasswords()
        }
    }

    private fun seedAddresses(): Set<String> = parseAddresses(seedGoogleEmailsRaw)

    /**
     * The addresses `springchat3.admin-emails` marks as administrators. Like
     * [seedAddresses] this is read on exactly one occasion - see [seedUsers] -
     * and it does **not** decide who exists: it only marks rows the Google
     * seed is creating anyway. An admin address that is not also in
     * `allowed-emails` would otherwise become a row with no way to sign in at
     * all, which is the half-written entry [KnownUsers] refuses to honour.
     * [warnAboutUnseededAddresses] names that case instead of inventing a
     * sign-in method for it.
     */
    private fun adminSeedAddresses(): Set<String> = parseAddresses(seedAdminEmailsRaw)

    private fun parseAddresses(raw: String): Set<String> =
        raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /**
     * The other half of the one-shot seed, and the more important half in
     * practice: an existing `users.json` is never rewritten from the env var,
     * so an address still listed there but missing from the file is somebody
     * who quietly cannot sign in any more. Saying so at boot is the
     * difference between a two-line fix and a bug report from the person
     * locked out - the failure is invisible from the server's side, because
     * "not in the roster" and "never was in the roster" look the same.
     */
    private fun warnAboutUnseededAddresses() {
        val missing = seedAddresses().filter { seed -> users.none { it.email == seed && it.google } }
        // Deliberately not an early return on an empty list. The admin half
        // below is a separate roster with a separate answer, and the common
        // case - every allowed address already has its row - is exactly the
        // one where an early return here would mean the admin warning never
        // fired at all. Which is the install that needs it most: the variable
        // stopped meaning anything the moment admin moved onto the row.
        if (missing.isNotEmpty()) {
            log.warn(
                "springchat3.allowed-emails still lists {} that {} has no Google row for: {}. Nobody is added from " +
                    "that variable once the file exists - add `\"google\": true` rows for them, or drop them from " +
                    "SPRINGCHAT3_ALLOWED_EMAILS if they are meant to be gone.",
                if (missing.size == 1) "an address" else "addresses",
                storeFile(),
                missing.joinToString(", "),
            )
        }
        warnAboutUnseededAdmins()
    }

    /**
     * The same diagnostic for the admin half (2026-08-26). It matters more
     * than the Google one on an *existing* install, which is every install
     * that already had a `users.json` when admin moved onto the row: the
     * variable keeps its old value in `.env` and quietly stops meaning
     * anything, so the person who set it is not an administrator any more and
     * the only symptom is a settings section that will not take a click.
     */
    private fun warnAboutUnseededAdmins() {
        val notAdmin = adminSeedAddresses().filter { seed -> users.none { it.email == seed && it.admin } }
        if (notAdmin.isEmpty()) return
        log.warn(
            "springchat3.admin-emails still lists {} that {} does not mark as an administrator: {}. Admin is a field " +
                "on the row now, read from that variable only when the file is first created - add `\"admin\": true` " +
                "to those rows (it takes effect without a restart), or drop them from SPRINGCHAT3_ADMIN_EMAILS.",
            if (notAdmin.size == 1) "an address" else "addresses",
            storeFile(),
            notAdmin.joinToString(", "),
        )
    }

    /**
     * Nobody can add users, and nobody can change server policy. Worth a line
     * at boot because there is no in-app way out of it: the screen that grants
     * admin is itself admin-only, on purpose, so the fix is a text editor.
     */
    private fun warnIfNoAdmin() {
        if (users.any { it.admin && !it.disabled }) return
        log.warn(
            "No account in {} is an administrator, so nobody can add users or change server policy from the app. " +
                "Add `\"admin\": true` to one row - the file re-reads itself, so this needs no restart.",
            storeFile(),
        )
    }

    /**
     * Writes the old `springchat3.allowed-emails` out as rows, once, on a
     * fresh install (2026-08-26) - the bootstrap that stops a new deployment
     * from having no way in and no UI to fix it. Only ever *adds*: an address
     * that already has a password account is marked rather than duplicated,
     * so one person stays one row.
     *
     * Deliberately not a removal path, and deliberately not reconciliation.
     * A seed that also deleted, or that re-ran against an existing file,
     * would mean the env var still governed the roster - and a revocation
     * made in the file would silently undo itself. See
     * [warnAboutUnseededAddresses] for what happens instead.
     */
    private fun seedUsers() {
        val seeds = seedAddresses()
        val adminSeeds = adminSeedAddresses()
        if (seeds.isEmpty()) {
            log.warn(
                "There is no {} and springchat3.allowed-emails is empty, so nobody can sign in at all. Create the " +
                    "file with one row per person - a plaintext `password`, or `\"google\": true`, or both.",
                storeFile(),
            )
            return
        }
        val known = users.map { it.email }.toSet()
        users = users.map { if (it.email in seeds) it.copy(google = true, admin = it.admin || it.email in adminSeeds) else it } +
            (seeds - known).sorted().map {
                AppUser(
                    email = it,
                    displayName = it.substringBefore('@'),
                    google = true,
                    admin = it in adminSeeds,
                    mustChangePassword = false,
                    createdAt = System.currentTimeMillis(),
                )
            }
        log.warn(
            "Seeded {} Google account(s) into a new {} from springchat3.allowed-emails, {} of them administrators " +
                "from springchat3.admin-emails. Both rosters now live in the file and will not be read from those " +
                "variables again - remove SPRINGCHAT3_ALLOWED_EMAILS and SPRINGCHAT3_ADMIN_EMAILS from .env so they " +
                "cannot diverge.",
            seeds.size,
            storeFile(),
            users.count { it.admin },
        )
        persist()
    }

    /** Null means the file exists but would not parse - distinct from "no accounts yet", which is an empty list. */
    private fun load(file: File): List<AppUser>? {
        if (!file.exists()) return emptyList()
        return try {
            objectMapper.readValue<List<AppUser>>(file)
                .filter { it.email.isNotBlank() }
                .map {
                    it.copy(
                        email = it.email.lowercase(),
                        mustChangePassword = it.mustChangePassword && (it.password != null || it.passwordHash != null),
                    )
                }
        } catch (e: Exception) {
            log.warn("Could not load accounts from {} - nobody will be able to sign in", file, e)
            null
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

    companion object {
        /**
         * Shared by [ch.arcticsoft.springchat3.web.AccountController] (a user
         * choosing their own) and
         * [ch.arcticsoft.springchat3.web.AdminUserController] (an admin
         * setting someone's first one), so the two cannot drift into
         * accepting different things.
         */
        const val MIN_PASSWORD_LENGTH = 10
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

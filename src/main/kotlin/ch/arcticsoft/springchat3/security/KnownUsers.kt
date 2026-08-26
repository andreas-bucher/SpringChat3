package ch.arcticsoft.springchat3.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * One person who is able to sign in, whichever way they do it - what `GET
 * /users` returns and what the share dialog picks from (2026-08-24, user's
 * own request "owners should be able to add members to their spaces" - see
 * springchat3_multi_user.md in project memory).
 *
 * [kind] is `"google"` or `"local"`, which the dialog shows as a small label:
 * useful because the two are set up in completely different places (an env
 * var vs `users.json`), so knowing which one an address belongs to is the
 * difference between knowing where to go and guessing.
 *
 * Carries no password, no roles and no per-space anything - it is a roster of
 * addresses, nothing more.
 */
data class KnownUser(
    val email: String,
    val displayName: String,
    val kind: String,
)

/**
 * The one answer to "can this address sign in at all?" (2026-08-24).
 *
 * Two rosters, deliberately kept in the two places they are administered -
 * `springchat3.allowed-emails` for Google accounts, `users.json` for password
 * accounts (see [UserStore]) - joined here rather than merged on disk.
 * This class is what stops that split becoming a trap: [SecurityConfig]'s
 * sign-in check and [ch.arcticsoft.springchat3.web.ProjectController]'s
 * "can this person actually accept?" check now read the *same* parsed set,
 * so the app can never allow someone in through one path while telling an
 * owner they don't exist through the other.
 */
@Component
class KnownUsers(
    @Value("\${springchat3.allowed-emails}") private val allowedEmailsRaw: String,
    private val userStore: UserStore,
) {
    /**
     * Comma-separated, trimmed, lowercased, blanks dropped. Parsed once -
     * this is a startup config value, unlike `users.json`, which
     * [UserStore] re-reads whenever it changes.
     */
    private val allowedGoogleEmails: Set<String> by lazy {
        allowedEmailsRaw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Whether [email] may sign in with Google - [SecurityConfig]'s own allow-list check. */
    fun isAllowedGoogleEmail(email: String?): Boolean =
        email != null && email.lowercase() in allowedGoogleEmails

    /**
     * Whether [email] can sign in by any means. Used before an owner shares a
     * space with someone: an address in neither roster would be stored
     * happily and then never match anyone, which looks from the owner's side
     * exactly like sharing being broken.
     */
    fun canSignIn(email: String): Boolean =
        isAllowedGoogleEmail(email) || userStore.find(email) != null

    /**
     * Everyone who can sign in, Google accounts first, then local ones,
     * each alphabetically. An address in both rosters appears once, as
     * Google - the sign-in page offers that button first, and a duplicate
     * row in a picker is worse than an imprecise label on an unusual case.
     */
    fun list(): List<KnownUser> {
        val google = allowedGoogleEmails.sorted().map { KnownUser(it, it.substringBefore('@'), "google") }
        val local = userStore.list()
            .filterNot { it.email in allowedGoogleEmails }
            .sortedBy { it.email }
            .map { KnownUser(it.email, it.displayName.ifBlank { it.email.substringBefore('@') }, "local") }
        return google + local
    }
}

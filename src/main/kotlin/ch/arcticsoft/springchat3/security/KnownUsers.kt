package ch.arcticsoft.springchat3.security

import org.springframework.stereotype.Component

/**
 * One person who is able to sign in, whichever way they do it - what `GET
 * /users` returns and what the share dialog picks from (2026-08-24, user's
 * own request "owners should be able to add members to their spaces" - see
 * springchat3_multi_user.md in project memory).
 *
 * [kind] is `"google"` or `"local"`, which the dialog shows as a small label.
 * It used to be the more useful of the two facts, because the two rosters
 * were administered in completely different places and knowing which one an
 * address belonged to was the difference between knowing where to go and
 * guessing. Since 2026-08-26 both are rows in `users.json`, so it is now
 * only a label for how the person proves who they are.
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
 * Originally this class existed to *join* two rosters kept in the two places
 * they were administered - `springchat3.allowed-emails` for Google accounts,
 * `users.json` for password accounts - so that [SecurityConfig]'s sign-in
 * check and [ch.arcticsoft.springchat3.web.ProjectController]'s "can this
 * person actually accept?" check could never disagree.
 *
 * Since 2026-08-26 there is only one roster to read: a Google account is a
 * row in `users.json` with `google: true` (see [UserStore]). The class stays
 * because the question is still worth naming and three call sites already ask
 * it - but it is now a view over [UserStore] rather than a join, which is
 * why every method here is three lines. The disagreement it was built to
 * prevent is no longer expressible.
 *
 * Every check honours [AppUser.disabled], so revoking someone is one field on
 * one row and takes effect on their next request - not an env-var edit and a
 * restart.
 */
@Component
class KnownUsers(
    private val userStore: UserStore,
) {
    /** Whether [email] may sign in with Google - [SecurityConfig]'s own allow-list check. */
    fun isAllowedGoogleEmail(email: String?): Boolean {
        val user = email?.let { userStore.find(it) } ?: return false
        return user.google && !user.disabled
    }

    /**
     * Whether [email] can sign in by any means. Used before an owner shares a
     * space with someone: an address in neither roster would be stored
     * happily and then never match anyone, which looks from the owner's side
     * exactly like sharing being broken.
     *
     * A row with neither `google` nor a password hash is not a way in - it is
     * a half-written entry, and answering true for it would recreate exactly
     * the "stored happily, matches nobody" bug this guards.
     */
    fun canSignIn(email: String): Boolean {
        val user = userStore.find(email) ?: return false
        return !user.disabled && (user.google || user.hasPassword)
    }

    /**
     * Everyone who can sign in, Google accounts first, then local ones, each
     * alphabetically. One row is one entry, so the de-duplication the two
     * rosters used to need is gone: an address that does both is a single row
     * reporting itself as Google.
     */
    fun list(): List<KnownUser> =
        userStore.list()
            .filter { !it.disabled && (it.google || it.hasPassword) }
            .map { KnownUser(it.email, it.displayName.ifBlank { it.email.substringBefore('@') }, it.kind) }
            .sortedWith(compareBy<KnownUser> { if (it.kind == "google") 0 else 1 }.thenBy { it.email })
}

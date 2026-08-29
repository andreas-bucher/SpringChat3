package ch.arcticsoft.springchat3.security

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Who may change server policy - the settings that are not one person's
 * preference (2026-08-25): whether the agent may edit documents at all, and
 * which Ollama models the settings popup is allowed to offer. Since
 * 2026-08-26 it also gates [ch.arcticsoft.springchat3.web.AdminUserController],
 * the screen that decides who can sign in at all.
 *
 * **This was a comma-separated env var until 2026-08-26**, and the reason
 * given for that - a Google account has no `users.json` row, so admin-by-flag
 * would mean inventing a password account for someone purely to make them an
 * admin - died earlier the same day when the Google roster moved into that
 * file. It was then the last roster in the app that needed a server restart
 * to change. It is now [AppUser.admin], one field on one row, re-read
 * whenever the file's mtime moves like everything else there;
 * `springchat3.admin-emails` survives only as a bootstrap seed for a
 * `users.json` that does not exist yet (see [UserStore.seedUsers]).
 *
 * So this class is now a three-line view over [UserStore], the same shape
 * [KnownUsers] took for the same reason. What it is NOT is a place where the
 * answer gets computed twice: sign-in, sharing and policy all read the one
 * row, so they cannot disagree.
 *
 * **Unset still means nobody, not everybody.** Before any of this existed,
 * any signed-in user could flip those settings for the whole server, so
 * failing open here would keep that bug rather than fix it - the same
 * fail-closed choice [ch.arcticsoft.springchat3.project.SpaceAccess] makes
 * for a missing identity. A roster with no administrator in it is warned
 * about at startup by [UserStore], because the symptom otherwise ("the toggle
 * does nothing") looks like a broken UI.
 *
 * A [AppUser.disabled] row is not an administrator. Revoking someone is meant
 * to be one field, and it would be a poor kind of revocation that left them
 * able to un-revoke themselves.
 */
@Component
class Admins(
    private val userStore: UserStore,
) {
    fun isAdmin(email: String?): Boolean {
        val user = email?.let { userStore.find(it) } ?: return false
        return user.admin && !user.disabled
    }

    /** Throws 403 unless [email] is an admin - the server-side half of the popup disabling its policy section. */
    fun requireAdmin(email: String) {
        if (!isAdmin(email)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can change this setting")
        }
    }

    /**
     * Every administrator who could still sign in. Exists for one caller:
     * the guardrail that refuses a change which would leave the app with no
     * administrator at all - a state with no way out except a text editor on
     * the server.
     */
    fun adminEmails(): Set<String> =
        userStore.list().filter { it.admin && !it.disabled }.map { it.email }.toSet()
}

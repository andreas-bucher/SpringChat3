package ch.arcticsoft.springchat3.security

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Who may change server policy - the settings that are not one person's
 * preference (2026-08-25, see springchat3_settings.md in project memory):
 * whether the agent may edit documents at all, and which Ollama models the
 * settings popup is allowed to offer.
 *
 * Administered exactly like [KnownUsers]'s Google roster - a comma-separated
 * env var - rather than as a flag on `users.json`: a Google account has no
 * `users.json` row, so putting it there would mean creating a password
 * account for someone purely to make them an admin, which is the two-rosters
 * trap [KnownUsers] itself exists to avoid.
 *
 * **Unset means nobody, not everybody.** Before this existed any signed-in
 * user could flip these for the whole server, so failing open here would keep
 * the bug rather than fix it - the same fail-closed choice
 * [ch.arcticsoft.springchat3.project.SpaceAccess] makes for a missing
 * identity. A blank list is logged at startup, because the symptom otherwise
 * ("the toggle does nothing") looks like a broken UI.
 */
@Component
class Admins(
    @Value("\${springchat3.admin-emails:}") private val adminEmailsRaw: String,
) {
    private val log = LoggerFactory.getLogger(Admins::class.java)

    /** Comma-separated, trimmed, lowercased, blanks dropped - same parsing as [KnownUsers]'s allow-list. */
    private val adminEmails: Set<String> by lazy {
        adminEmailsRaw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    @PostConstruct
    fun warnIfUnset() {
        if (adminEmails.isEmpty()) {
            log.warn(
                "springchat3.admin-emails is empty, so nobody can change server policy (document editing, the model " +
                    "allow-list) from the settings popup - it will show those as read-only for everyone. Set " +
                    "SPRINGCHAT3_ADMIN_EMAILS to a comma-separated list of addresses to enable it.",
            )
        }
    }

    fun isAdmin(email: String?): Boolean = email != null && email.lowercase() in adminEmails

    /** Throws 403 unless [email] is an admin - the server-side half of the popup hiding its policy section. */
    fun requireAdmin(email: String) {
        if (!isAdmin(email)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can change this setting")
        }
    }
}

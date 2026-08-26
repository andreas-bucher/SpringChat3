package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.project.SpaceAccess
import ch.arcticsoft.springchat3.security.UserStore
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

/** `POST /account/password`'s request body. */
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

/**
 * Lets a local account change its own password (2026-08-24, local accounts -
 * see springchat3_local_accounts.md in project memory) - the other half of
 * the admin-sets-the-first-password design, and the only way out of the
 * [ch.arcticsoft.springchat3.security.SessionGateWebFilter] block a new
 * account starts in.
 *
 * Changing *your own* password only: the email comes from the session, never
 * from the request body, so this endpoint cannot be pointed at anyone else's
 * account. A Google session has no password to change and gets `409` -
 * Google owns that credential, not this app.
 */
@RestController
class AccountController(
    private val userStore: UserStore,
    private val passwordEncoder: PasswordEncoder,
    private val spaceAccess: SpaceAccess,
) {
    @PostMapping("/account/password")
    fun changePassword(@RequestBody request: ChangePasswordRequest, exchange: ServerWebExchange) {
        val email = spaceAccess.currentUserEmail(exchange)
        val user = userStore.find(email)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "This account signs in with Google - change your password there.")
        val hash = user.passwordHash
        if (hash == null || !passwordEncoder.matches(request.currentPassword, hash)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "That is not your current password.")
        }
        val fresh = request.newPassword
        if (fresh.length < MIN_PASSWORD_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Please choose at least $MIN_PASSWORD_LENGTH characters.")
        }
        if (passwordEncoder.matches(fresh, hash)) {
            // Not pedantry: the whole point of the forced first change is
            // that the admin knows the current password, and "change" it to
            // the same thing would clear the flag without fixing that.
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Please choose a password you have not used here before.")
        }
        userStore.changePassword(email, fresh)
    }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 10
    }
}

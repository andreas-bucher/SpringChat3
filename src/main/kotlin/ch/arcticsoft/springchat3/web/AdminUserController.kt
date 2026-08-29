package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.project.ProjectStore
import ch.arcticsoft.springchat3.project.SpaceAccess
import ch.arcticsoft.springchat3.project.SpaceMember
import ch.arcticsoft.springchat3.security.Admins
import ch.arcticsoft.springchat3.security.AppUser
import ch.arcticsoft.springchat3.security.UserStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

/**
 * One account as the Users screen sees it. Everything [AppUser] holds except
 * the two fields that must never leave the server - [AppUser.passwordHash]
 * and the plaintext bootstrap [AppUser.password] - plus [ownedSpaces], which
 * the screen needs before it offers to delete anyone.
 */
data class AdminUserView(
    val email: String,
    val displayName: String,
    val kind: String,
    val google: Boolean,
    val hasPassword: Boolean,
    val admin: Boolean,
    val disabled: Boolean,
    val mustChangePassword: Boolean,
    val createdAt: Long,
    /** How many spaces this address owns. Non-zero makes deletion a refusal - see [delete]. */
    val ownedSpaces: Int,
    /** Whether this row is the caller's own, so the screen can grey out what it will refuse anyway. */
    val self: Boolean,
)

/** `POST /admin/users`'s body. A blank [password] means Google-only, and [google] false with a password means the reverse. */
data class CreateUserRequest(
    val email: String = "",
    val displayName: String = "",
    val google: Boolean = false,
    val password: String? = null,
    val admin: Boolean = false,
)

/** `PATCH /admin/users`'s body - null means "leave this one alone", so the screen can send one field at a time. */
data class UpdateUserRequest(
    val displayName: String? = null,
    val google: Boolean? = null,
    val admin: Boolean? = null,
    val disabled: Boolean? = null,
)

/** `POST /admin/users/password`'s body - an administrator setting someone else's first or replacement password. */
data class SetPasswordRequest(val password: String = "")

/**
 * The Users screen (2026-08-26, user's own request: *"Add role Admin so the
 * springchat3.emails is not needed anymore. Admin Screen shows all users.
 * Admin Screen enables to add new users with password. Admin Screen enables
 * to add new users having google accounts (allow list)."*).
 *
 * It is a CRUD screen over [UserStore], which is why that store had to move
 * first: until 2026-08-26 the Google allow-list was an env var, and a page
 * cannot administer a roster that only exists in the process's environment.
 *
 * **This controller writes the authentication boundary.** Everything else in
 * this app answers "what may this person see"; these five routes answer "who
 * is a person at all". A bug in one of them is not a wrong setting, it is
 * someone getting in - so:
 *
 * - **every route calls [caller] first**, before it looks at a body or a
 *   parameter. That is the whole gate, in one method, so an audit is a grep
 *   rather than a reading of five handlers;
 * - the address is a **query parameter, never a path variable** - the same
 *   choice `DELETE /projects/{id}/members` made, for the same reason: an
 *   email in a path invites encoding questions for no benefit;
 * - **no password material is ever returned** (see [AdminUserView]);
 * - the guardrails below are all here rather than in [UserStore], because
 *   each of them needs to know who is asking, and the store deliberately does
 *   not.
 *
 * **Four things it refuses**, all of which are ways to end up locked out of
 * an app whose only unlock is a text editor on the server:
 * 1. removing your own administrator rights, disabling yourself, deleting
 *    yourself, or taking away your own last way to sign in;
 * 2. removing the last administrator, whoever asks;
 * 3. leaving any row with neither Google nor a password - not a lockout of
 *    the server but of that person, and an entry that matches nobody looks
 *    exactly like sharing being broken (see [ch.arcticsoft.springchat3.security.KnownUsers]);
 * 4. deleting anyone who still owns a space, since space deletion is
 *    owner-only and the space would be left with an owner nobody can be.
 *
 * Revoking access is [AppUser.disabled], not deletion, for that last reason.
 * Deletion stays available because the user asked for it, but only where it
 * strands nothing.
 */
@RestController
@RequestMapping("/admin/users")
class AdminUserController(
    private val userStore: UserStore,
    private val admins: Admins,
    private val spaceAccess: SpaceAccess,
    private val projectStore: ProjectStore,
) {
    /**
     * Who is asking, and a 403 unless they may. Called as the first statement
     * of every handler - a route that forgets is the only way this screen can
     * go wrong, so it is one call with a name rather than a repeated pair.
     */
    private fun caller(exchange: ServerWebExchange): String {
        val email = spaceAccess.currentUserEmail(exchange)
        admins.requireAdmin(email)
        return email
    }

    private fun ownedSpaces(email: String): Int =
        projectStore.list().count { it.owner?.equals(email, ignoreCase = true) == true }

    private fun view(user: AppUser, me: String) = AdminUserView(
        email = user.email,
        displayName = user.displayName,
        kind = user.kind,
        google = user.google,
        hasPassword = user.hasPassword,
        admin = user.admin,
        disabled = user.disabled,
        mustChangePassword = user.mustChangePassword,
        createdAt = user.createdAt,
        ownedSpaces = ownedSpaces(user.email),
        self = user.email.equals(me, ignoreCase = true),
    )

    private fun requireUser(user: AppUser?): AppUser =
        user ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "There is no account for that address.")

    private fun refuse(message: String): Nothing =
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    /**
     * Administrators first, then everyone else, each alphabetically - the
     * same ordering [ch.arcticsoft.springchat3.security.KnownUsers.list] uses
     * for its own grouping, and it puts the rows whose state matters most at
     * the top of a list that will mostly be unremarkable.
     *
     * Unlike `GET /users` this one shows disabled accounts: a revoked account
     * that vanished from the screen could never be restored from it.
     */
    @GetMapping
    fun list(exchange: ServerWebExchange): List<AdminUserView> {
        val me = caller(exchange)
        return userStore.list()
            .map { view(it, me) }
            .sortedWith(compareByDescending<AdminUserView> { it.admin }.thenBy { it.email })
    }

    @PostMapping
    fun create(@RequestBody request: CreateUserRequest, exchange: ServerWebExchange): AdminUserView {
        val me = caller(exchange)
        val email = request.email.trim().lowercase()
        if (!EMAIL_PATTERN.matches(email) || email == SpaceMember.EVERYONE) {
            refuse("That does not look like an email address.")
        }
        val password = request.password?.takeIf { it.isNotBlank() }
        if (!request.google && password == null) {
            refuse("Give the account a password, Google sign-in, or both - an account with neither cannot sign in.")
        }
        if (password != null && password.length < UserStore.MIN_PASSWORD_LENGTH) {
            refuse("Please choose at least ${UserStore.MIN_PASSWORD_LENGTH} characters.")
        }
        val created = userStore.create(email, request.displayName, request.google, password, request.admin, me)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "There is already an account for that address.")
        return view(created, me)
    }

    @PatchMapping
    fun update(
        @RequestParam email: String,
        @RequestBody request: UpdateUserRequest,
        exchange: ServerWebExchange,
    ): AdminUserView {
        val me = caller(exchange)
        val target = requireUser(userStore.find(email))
        val isSelf = target.email.equals(me, ignoreCase = true)

        if (isSelf) {
            if (request.admin == false) {
                refuse("You cannot remove your own administrator rights - ask another administrator to do it.")
            }
            if (request.disabled == true) refuse("You cannot disable your own account.")
        }
        // The last administrator, whoever is asking. Self-demotion is already
        // refused above, so this is the other route to the same dead end:
        // demoting or disabling the only other admin while you are not one.
        val losesAdmin = request.admin == false || request.disabled == true
        if (losesAdmin && target.admin && admins.adminEmails() == setOf(target.email)) {
            refuse("${target.email} is the only administrator left - make someone else one first.")
        }
        // Written on the change, not on the resulting state: a row that
        // already has neither - a half-written entry someone left in the file
        // by hand - must still be editable, and the way to fix it is exactly
        // the request that would fail a resulting-state check.
        if (request.google == false && !target.hasPassword) {
            val whose = if (isSelf) "your own account" else target.email
            refuse("That would leave $whose with no way to sign in - disable the account instead.")
        }
        val updated = requireUser(
            userStore.update(
                email = target.email,
                displayName = request.displayName,
                google = request.google,
                admin = request.admin,
                disabled = request.disabled,
                by = me,
            ),
        )
        return view(updated, me)
    }

    /**
     * Sets someone's password to one the administrator chose, which
     * [UserStore.setPassword] pairs with [AppUser.mustChangePassword] - so
     * the recipient replaces it at their next sign-in and the admin never
     * knows the password that ends up stored.
     *
     * Not for your own account: it would set that flag on the session doing
     * the setting, and [ch.arcticsoft.springchat3.security.SessionGateWebFilter]
     * would immediately reduce the app to the password prompt. Changing your
     * own is `POST /account/password`, which asks for the current one.
     */
    @PostMapping("/password")
    fun setPassword(
        @RequestParam email: String,
        @RequestBody request: SetPasswordRequest,
        exchange: ServerWebExchange,
    ): AdminUserView {
        val me = caller(exchange)
        val target = requireUser(userStore.find(email))
        if (target.email.equals(me, ignoreCase = true)) {
            refuse("Change your own password from the prompt the app gives you, not from here.")
        }
        if (request.password.length < UserStore.MIN_PASSWORD_LENGTH) {
            refuse("Please choose at least ${UserStore.MIN_PASSWORD_LENGTH} characters.")
        }
        return view(requireUser(userStore.setPassword(target.email, request.password, me)), me)
    }

    @DeleteMapping
    fun delete(@RequestParam email: String, exchange: ServerWebExchange) {
        val me = caller(exchange)
        val target = requireUser(userStore.find(email))
        if (target.email.equals(me, ignoreCase = true)) refuse("You cannot delete your own account.")
        if (target.admin && admins.adminEmails() == setOf(target.email)) {
            refuse("${target.email} is the only administrator left - make someone else one first.")
        }
        val owned = ownedSpaces(target.email)
        if (owned > 0) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "${target.email} owns $owned space${if (owned == 1) "" else "s"}. Disable the account instead - " +
                    "deleting the row would leave those spaces owned by an address nobody can sign in as.",
            )
        }
        userStore.delete(target.email, me)
    }

    companion object {
        /**
         * Deliberately loose. This is a roster an administrator types into,
         * not a signup form: the only thing worth rejecting is something that
         * clearly is not an address, because a typo that *looks* like one is
         * caught by the person it fails to let in, and a pattern strict
         * enough to be interesting rejects valid addresses instead.
         */
        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

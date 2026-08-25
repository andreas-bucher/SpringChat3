package ch.arcticsoft.springchat3.project

import ch.arcticsoft.springchat3.security.CURRENT_USER_EMAIL_ATTRIBUTE
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

/**
 * What one user may do in one space. OWNER is [Project.owner]; EDITOR and
 * VIEWER come from a [SpaceMember] entry. An editor can do everything with a
 * space's contents (upload, link, sync, delete, and edit documents through
 * the chat agent); a viewer reads, previews and asks questions but changes
 * nothing. Only the owner may re-share the space.
 */
enum class SpaceRole { OWNER, EDITOR, VIEWER }

/**
 * One person a space is shared with. [email] is matched case-insensitively
 * against the Google account's own email, or is [EVERYONE] for "anyone who
 * can sign in at all" - which is not as broad as it sounds, since
 * [ch.arcticsoft.springchat3.security.SecurityConfig]'s allow-list decides
 * who that is.
 *
 * A member entry is never [SpaceRole.OWNER] - ownership is [Project.owner],
 * one field, so that "who may re-share this" has exactly one answer.
 * [SpaceAccess.roleOf] ignores an OWNER role found here rather than
 * honouring it.
 */
data class SpaceMember(
    val email: String,
    val role: SpaceRole = SpaceRole.EDITOR,
) {
    companion object {
        const val EVERYONE = "*"
    }
}

/**
 * The one place that decides who may see or change what (2026-08-24, user's
 * own request "It should be possible that users have their own spaces. It
 * should be possible that some spaces are shared across users" - see
 * springchat3_multi_user.md in project memory for the whole design).
 *
 * Every controller asks this rather than reasoning about [Project.owner] and
 * [Project.members] itself. That matters more here than the usual
 * don't-repeat-yourself argument: until this existed, `GET /documents` and
 * `GET /chat-history` returned **everything** and index.html filtered by
 * `spaceId` client-side, so "private" would have been a UI convention rather
 * than a rule. Anything that filters or validates has to go through one
 * object, or the next endpoint added quietly won't.
 *
 * **The two legacy rules**, which exist so that nothing already on disk
 * changes behaviour and no migration is needed - the same choice
 * [ch.arcticsoft.springchat3.document.SpaceScopedJsonStore] made:
 * - a space whose [Project.owner] is null (every space created before this
 *   change) is an [SpaceRole.EDITOR] for everyone signed in - exactly
 *   today's behaviour;
 * - a null `spaceId` (a document uploaded with no space active) is likewise
 *   an editor for everyone. New spaces always get an owner, so both rules
 *   only ever apply to data that predates this.
 *
 * **Fails closed.** A missing identity is a `401`, never an anonymous pass:
 * if [ch.arcticsoft.springchat3.security.CurrentUserWebFilter] were
 * unwired, every request would be rejected rather than every request
 * sailing through as nobody.
 */
@Component
class SpaceAccess(
    private val projectStore: ProjectStore,
) {
    /**
     * Who is asking. `401` if
     * [ch.arcticsoft.springchat3.security.CurrentUserWebFilter] found no
     * authenticated user - see this class's own doc comment on failing
     * closed.
     */
    fun currentUserEmail(exchange: ServerWebExchange): String =
        exchange.getAttribute<String>(CURRENT_USER_EMAIL_ATTRIBUTE)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in")

    /**
     * [email]'s effective role in [spaceId], or null if they have no access
     * at all. See this class's own doc comment for the two legacy rules that
     * make a null [spaceId] and an unowned space readable and writable by
     * everyone.
     *
     * A specific member entry wins over an [SpaceMember.EVERYONE] one, so
     * sharing a space with everyone as a viewer and with one colleague as an
     * editor does what it looks like.
     */
    fun roleOf(email: String, spaceId: String?): SpaceRole? {
        if (spaceId == null) return SpaceRole.EDITOR
        val space = projectStore.get(spaceId) ?: return null
        val owner = space.owner ?: return SpaceRole.EDITOR
        if (owner.equals(email, ignoreCase = true)) return SpaceRole.OWNER
        val entry = space.members.firstOrNull { it.email.equals(email, ignoreCase = true) }
            ?: space.members.firstOrNull { it.email == SpaceMember.EVERYONE }
            ?: return null
        return if (entry.role == SpaceRole.OWNER) SpaceRole.EDITOR else entry.role
    }

    /** Every space the caller may see at all, in [ProjectStore.list]'s own oldest-first order. */
    fun visibleSpaces(exchange: ServerWebExchange): List<Project> {
        val email = currentUserEmail(exchange)
        return projectStore.list().filter { roleOf(email, it.spaceId) != null }
    }

    /**
     * The ids of [visibleSpaces], for filtering a list of documents/pages/
     * links that carries a `spaceId` per row. **A null `spaceId` is not in
     * this set** and must be allowed separately - see [canRead], which
     * every such filter should use instead of a bare `in` check.
     */
    fun visibleSpaceIds(exchange: ServerWebExchange): Set<String> =
        visibleSpaces(exchange).map { it.spaceId }.toSet()

    /** Whether the caller may see [spaceId]'s contents. */
    fun canRead(exchange: ServerWebExchange, spaceId: String?): Boolean =
        roleOf(currentUserEmail(exchange), spaceId) != null

    /** Whether the caller may change [spaceId]'s contents - true for an owner or an editor, false for a viewer. */
    fun canWrite(exchange: ServerWebExchange, spaceId: String?): Boolean =
        roleOf(currentUserEmail(exchange), spaceId).let { it == SpaceRole.OWNER || it == SpaceRole.EDITOR }

    /** `403` unless the caller may see [spaceId]'s contents. */
    fun requireRead(exchange: ServerWebExchange, spaceId: String?) {
        if (!canRead(exchange, spaceId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this space")
        }
    }

    /**
     * `403` unless the caller may change [spaceId]'s contents. A viewer gets
     * a different message from a stranger on purpose - "you can look at this
     * but not change it" is a state worth being able to tell apart from
     * "this isn't yours" when something in the UI unexpectedly fails.
     */
    fun requireWrite(exchange: ServerWebExchange, spaceId: String?) {
        val role = roleOf(currentUserEmail(exchange), spaceId)
        if (role == null) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this space")
        }
        if (role == SpaceRole.VIEWER) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You have view-only access to this space")
        }
    }

    /** `403` unless the caller owns [spaceId] - for re-sharing and, once it exists, deleting a space. */
    fun requireOwner(exchange: ServerWebExchange, spaceId: String?) {
        if (roleOf(currentUserEmail(exchange), spaceId) != SpaceRole.OWNER) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner of this space can do that")
        }
    }
}

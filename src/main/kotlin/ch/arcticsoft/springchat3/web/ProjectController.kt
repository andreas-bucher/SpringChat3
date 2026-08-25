package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.project.Project
import ch.arcticsoft.springchat3.project.ProjectStore
import ch.arcticsoft.springchat3.project.SpaceAccess
import ch.arcticsoft.springchat3.project.SpaceDeletionService
import ch.arcticsoft.springchat3.project.SpaceMember
import ch.arcticsoft.springchat3.project.SpaceRole
import ch.arcticsoft.springchat3.security.KnownUsers
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

/**
 * `POST /projects`'s request body - [description] is nullable rather than
 * defaulted (same convention as [ModelOverrideRequest.model]) since only
 * [name] is required; a missing/null value is treated as blank in
 * [ProjectController.create].
 */
data class CreateProjectRequest(val name: String, val description: String?)

/** `PATCH /projects/{spaceId}`'s body. Only the name is renameable; description was never editable and is left alone. */
data class RenameProjectRequest(val name: String)

/**
 * `POST /projects/{spaceId}/members`' request body. [role] is a plain
 * `String` rather than a [SpaceRole] so that a wrong value produces this
 * app's own explanation of which roles exist, instead of Jackson's
 * deserialization error - the difference between a message an owner can act
 * on and one they can only forward.
 */
data class AddMemberRequest(val email: String, val role: String?)

/**
 * Backs the left-hand Projects panel's "New Project" popup (2026-08-23, see
 * springchat3_projects_panel.md in project memory) - create + list only for
 * now, matching what was actually asked for; no rename/delete endpoint yet.
 */
@RestController
class ProjectController(
    private val projectStore: ProjectStore,
    private val spaceAccess: SpaceAccess,
    private val knownUsers: KnownUsers,
    private val spaceDeletionService: SpaceDeletionService,
) {
    /**
     * The spaces *this caller* may see, oldest first - backs the left
     * panel's Spaces list on page load (2026-08-24; it used to return every
     * space that existed, see [SpaceAccess]).
     *
     * This is also what bounds the whole left panel: index.html only ever
     * renders resources under a space it got from here, so a space filtered
     * out at this endpoint takes its documents, chats and links with it.
     * That is a convenience, not the enforcement - every other endpoint
     * checks for itself, because a browser is not where this can be decided.
     */
    @GetMapping("/projects")
    fun list(exchange: ServerWebExchange): List<Project> = spaceAccess.visibleSpaces(exchange)

    /**
     * `400 Bad Request` if [CreateProjectRequest.name] is blank -
     * [ProjectStore.create] itself trusts its caller and doesn't re-check.
     * The creator becomes the space's owner, so a new space is private until
     * they share it.
     */
    @PostMapping("/projects")
    fun create(@RequestBody request: CreateProjectRequest, exchange: ServerWebExchange): Project {
        val name = request.name.trim()
        if (name.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is required")
        }
        return projectStore.create(name, request.description?.trim() ?: "", spaceAccess.currentUserEmail(exchange))
    }

    /**
     * Renames [spaceId] (2026-08-25).
     *
     * **Write access, not owner-only** - deliberately weaker than deleting or
     * re-sharing, and the difference is the point. Those two are irreversible
     * or hand out access; a rename destroys nothing and is undone by typing
     * the old name back. Owner-only would also have made the feature dead on
     * arrival for this installation: [SpaceAccess.roleOf] returns EDITOR and
     * never OWNER for a space with `owner == null`, so every space created
     * before ownership existed would refuse it - exactly as they already
     * refuse deletion.
     *
     * `400` for a blank name, matching [create]'s own check, since
     * [ProjectStore.rename] trusts its caller the same way
     * [ProjectStore.create] does.
     */
    @PatchMapping("/projects/{spaceId}")
    fun rename(
        @PathVariable spaceId: String,
        @RequestBody request: RenameProjectRequest,
        exchange: ServerWebExchange,
    ): Project {
        projectStore.get(spaceId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such space")
        spaceAccess.requireWrite(exchange, spaceId)
        val name = request.name.trim()
        if (name.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Space name is required")
        }
        return projectStore.rename(spaceId, name)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such space")
    }

    /**
     * Deletes [spaceId] and everything in it (2026-08-24, user's own request
     * "Currently it is not supported to delete a space"). Owner only, which
     * has one consequence worth knowing: a space from before ownership
     * existed has no owner, so **nobody can delete it** until one is set by
     * hand in its `space.json`. That falls out of [SpaceAccess.roleOf]
     * rather than being a special case here, and it is the right default for
     * the first destructive action in this app.
     *
     * `204 No Content` on success, and on a space that was already gone -
     * "it no longer exists" is the only thing the caller is asking for, and
     * a `404` for a second click on the same button would be noise.
     *
     * What deleting does NOT do: touch anything in Google Drive. A linked
     * folder's files and a linked Google Doc are only ever *copied* into
     * this app (see [SpaceDeletionService]), and the copy is what goes.
     */
    @DeleteMapping("/projects/{spaceId}")
    fun delete(@PathVariable spaceId: String, exchange: ServerWebExchange): ResponseEntity<Void> {
        if (projectStore.get(spaceId) == null) return ResponseEntity.noContent().build()
        spaceAccess.requireOwner(exchange, spaceId)
        if (!spaceDeletionService.delete(spaceId)) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not delete this space - nothing was changed.",
            )
        }
        return ResponseEntity.noContent().build()
    }

    /**
     * Shares [spaceId] with one person, or changes the role they already
     * have (2026-08-24, user's own request "owners should be able to add
     * members to their spaces"). Owner only - a member, however privileged
     * otherwise, cannot widen the circle they were let into.
     *
     * Four rejections, each of which used to be a silent no-op when sharing
     * meant editing `space.json` by hand:
     * - a role other than EDITOR or VIEWER, **including OWNER** - ownership
     *   is [Project.owner] and a member entry never confers it (see
     *   [SpaceAccess.roleOf], which quietly demotes one);
     * - the owner adding themselves, which could only ever take rights away;
     * - an address that cannot sign in by any means (see
     *   [KnownUsers.canSignIn]) - stored happily, it would match nobody, and
     *   from the owner's side that is indistinguishable from sharing being
     *   broken;
     * - a blank address.
     *
     * [SpaceMember.EVERYONE] skips the sign-in check for the obvious reason
     * that it is not an address.
     *
     * Adding someone who is already a member replaces their entry rather
     * than duplicating it, so this is also how a role is changed.
     */
    @PostMapping("/projects/{spaceId}/members")
    fun addMember(
        @PathVariable spaceId: String,
        @RequestBody request: AddMemberRequest,
        exchange: ServerWebExchange,
    ): Project {
        spaceAccess.requireOwner(exchange, spaceId)
        val space = projectStore.get(spaceId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such space")

        val email = request.email.trim().lowercase()
        if (email.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "An email address is required")
        }
        val role = when (request.role?.trim()?.uppercase()) {
            null, "", "EDITOR" -> SpaceRole.EDITOR
            "VIEWER" -> SpaceRole.VIEWER
            "OWNER" -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A space has one owner and it cannot be granted here - share as an editor or a viewer.",
            )
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be EDITOR or VIEWER")
        }
        if (email.equals(space.owner, ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "They already own this space.")
        }
        if (email != SpaceMember.EVERYONE && !knownUsers.canSignIn(email)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$email cannot sign in yet - add them to SPRINGCHAT3_ALLOWED_EMAILS or to users.json first.",
            )
        }

        val members = space.members.filterNot { it.email.equals(email, ignoreCase = true) } + SpaceMember(email, role)
        return projectStore.updateMembers(spaceId, members)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such space")
    }

    /**
     * Stops sharing [spaceId] with [email]. Owner only, and deliberately
     * idempotent: removing someone who is not a member returns the space
     * unchanged rather than `404`, since "they no longer have access" is
     * true either way and that is the only thing the caller cares about.
     *
     * The address is a query parameter rather than a path variable - an
     * email in a URL path invites encoding questions for no benefit here.
     */
    @DeleteMapping("/projects/{spaceId}/members")
    fun removeMember(
        @PathVariable spaceId: String,
        @RequestParam email: String,
        exchange: ServerWebExchange,
    ): Project {
        spaceAccess.requireOwner(exchange, spaceId)
        val space = projectStore.get(spaceId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such space")
        val members = space.members.filterNot { it.email.equals(email.trim(), ignoreCase = true) }
        if (members.size == space.members.size) return space
        return projectStore.updateMembers(spaceId, members)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such space")
    }
}

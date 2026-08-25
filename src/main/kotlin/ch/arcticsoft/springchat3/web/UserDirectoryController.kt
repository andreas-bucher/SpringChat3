package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.security.KnownUser
import ch.arcticsoft.springchat3.security.KnownUsers
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Everyone who can sign in, so the share dialog can offer a list to pick
 * from instead of an address to type correctly (2026-08-24, user's own
 * request "owners should be able to add members to their spaces" - see
 * springchat3_multi_user.md in project memory).
 *
 * Addresses and display names only - no password material, no roles, no
 * per-space anything (see [KnownUser]). It is still a roster: **every
 * signed-in user can see who else may use this app**, which is the trade
 * this endpoint makes for a picker that cannot produce an address nobody
 * owns. Reasonable for an allow-listed handful of people; worth revisiting
 * before that list is long enough that its contents are not common
 * knowledge.
 *
 * No space in the path and nothing to filter per caller, so unlike every
 * other list endpoint in this app it asks
 * [ch.arcticsoft.springchat3.project.SpaceAccess] nothing. Being signed in
 * is the whole check, and that is
 * [ch.arcticsoft.springchat3.security.SecurityConfig]'s job.
 */
@RestController
class UserDirectoryController(
    private val knownUsers: KnownUsers,
) {
    @GetMapping("/users")
    fun list(): List<KnownUser> = knownUsers.list()
}

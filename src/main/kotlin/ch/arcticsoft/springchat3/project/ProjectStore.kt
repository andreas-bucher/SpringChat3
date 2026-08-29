package ch.arcticsoft.springchat3.project

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.Collections
import kotlin.random.Random

/**
 * ## Vocabulary note (2026-08-24)
 *
 * The user-facing name for this concept is a **Space**, and so is every name
 * that reaches disk or the wire: the folder `[dataDir]/spaces/<spaceId>/`,
 * the metadata file `space.json`, and the `spaceId` field on this and every
 * other persisted type ([ch.arcticsoft.springchat3.document.ExtractedDocument],
 * [ch.arcticsoft.springchat3.document.DriveLink], and the rest).
 *
 * The *Kotlin* names deliberately still say Project - this class, [Project],
 * [ch.arcticsoft.springchat3.web.ProjectController], the `/projects` routes,
 * the `ch.arcticsoft.springchat3.project` package. That was the scope the
 * user chose ("paths + JSON field names", not a full code rename), so expect
 * both vocabularies side by side and don't "fix" one half in isolation:
 * renaming the routes is an API change, and renaming the fields again would
 * be another data migration.
 *
 * ## This type
 *
 * One space's identity/metadata (2026-08-23, see
 * springchat3_projects_panel.md in project memory) - [spaceId] is a random
 * 6-digit string (e.g. "482913"), not a sequential/database id, per the
 * user's own request; it also doubles as this space's on-disk folder name
 * (see [ProjectStore]'s own doc comment).
 */
data class Project(
    val spaceId: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    /**
     * The email of whoever created this space (2026-08-24, "It should be
     * possible that users have their own spaces" - see
     * springchat3_multi_user.md in project memory), lowercased, matching the
     * Google account [ch.arcticsoft.springchat3.security.SecurityConfig]
     * signed them in as.
     *
     * **Null means "everyone", not "nobody".** Every space created before
     * this field existed has no owner, and [SpaceAccess] deliberately treats
     * such a space as shared with every signed-in user - which is exactly
     * how the whole app behaved until now, so nothing already on disk
     * changes and no migration is needed. Nullable for that reason alone;
     * anything created from here on always has one.
     */
    val owner: String? = null,
    /**
     * Who else this space is shared with, and as what - empty for a private
     * space. [owner] is never repeated here. See [SpaceMember] for the
     * [SpaceMember.EVERYONE] wildcard.
     */
    val members: List<SpaceMember> = emptyList(),
)

/**
 * Store for projects created via
 * [ch.arcticsoft.springchat3.web.ProjectController] (2026-08-23, user's own
 * request "the project shall get a unique 6 digit identifier. a project
 * folder shall be created with the project identifier as folder name. in
 * the project folder the project meta data are stored" - see
 * springchat3_projects_panel.md in project memory): each project gets its
 * own `[dataDir]/spaces/<spaceId>/space.json`, same per-item-directory
 * persistence pattern as
 * [ch.arcticsoft.springchat3.document.DocumentStore] (one file per item
 * rather than one shared JSON file, so a load/write failure is bounded to a
 * single project) - nested under a `spaces/` subdirectory, unlike
 * [DocumentStore]'s flat `[dataDir]/<documentId>/` layout, so a 6-digit
 * project id can never collide with a document directory (10-digit since
 * 2026-08-29, UUID-named before that - see [DocumentStore] for both), and
 * `data/spaces/` reads as a real, browsable "project folder" the way the
 * request describes.
 *
 * Same `LinkedHashMap` + `Collections.synchronizedMap` + eager-load-at-
 * startup approach as [DocumentStore] - see that class's own doc comment
 * for the full reasoning (insertion-order iteration for [list], single-user
 * app so the extra locking is cheap insurance rather than a real bottleneck).
 */
@Component
class ProjectStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
) {
    private val log = LoggerFactory.getLogger(ProjectStore::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val projects = Collections.synchronizedMap(loadPersisted())

    private fun spacesRoot() = File(dataDir, "spaces")

    /**
     * A project's own on-disk folder, `[dataDir]/spaces/<spaceId>`.
     * Public (2026-08-23, user's own request "when uploading a file or link
     * a google drive folder or link a working document then save the files
     * in the project folder of the active project" - see
     * springchat3_projects_panel.md in project memory) so
     * [ch.arcticsoft.springchat3.document.DocumentStore] can save a
     * project-scoped document's files inside this exact same folder instead
     * of independently recomputing the same path - one place decides where
     * a project's folder lives, not two formulas that could drift apart.
     * Doesn't require [spaceId] to be a real, currently-known project -
     * same "pure path function, no existence check" contract [DocumentStore]'s
     * own private `documentDir` already has.
     */
    fun spaceDir(spaceId: String): File = File(spacesRoot(), spaceId)

    /**
     * Every space folder that actually exists under `[dataDir]/spaces`, for
     * [ch.arcticsoft.springchat3.document.SpaceScopedJsonStore] to enumerate
     * the per-space files it splits its callers' lists across. Public for
     * the same reason [spaceDir] is: one place decides where a project's
     * folders live rather than a second copy of the same formula.
     *
     * Deliberately the *filesystem*, not [list] - a folder whose
     * `space.json` failed to parse is skipped by [loadPersisted] but may
     * still hold entries that were written there, and the caller writes by
     * space id without checking that the id is a known project.
     */
    fun spaceDirs(): List<File> = spacesRoot().listFiles { file: File -> file.isDirectory }?.toList() ?: emptyList()

    private fun spaceFile(spaceId: String) = File(spaceDir(spaceId), "space.json")

    private fun loadPersisted(): LinkedHashMap<String, Project> {
        val root = spacesRoot()
        root.mkdirs()
        val loaded = mutableListOf<Project>()
        root.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val file = File(dir, "space.json")
            if (file.exists()) {
                try {
                    loaded += objectMapper.readValue<Project>(file)
                } catch (e: Exception) {
                    log.warn("Could not load persisted project from {} - skipping", file, e)
                }
            }
        }
        loaded.sortBy { it.createdAt }
        val map = LinkedHashMap<String, Project>()
        loaded.forEach { map[it.spaceId] = it }
        return map
    }

    private fun persist(project: Project) {
        try {
            val dir = spaceDir(project.spaceId)
            dir.mkdirs()
            objectMapper.writeValue(spaceFile(project.spaceId), project)
        } catch (e: Exception) {
            log.warn("Could not persist project {} to {}", project.spaceId, spaceFile(project.spaceId), e)
        }
    }

    /**
     * A random 6-digit id ("100000".."999999", so it's always 6 digits -
     * never a shorter number that dropping a leading zero could produce).
     * Must be called while holding `synchronized(projects)` (see [create])
     * so the containsKey check and the eventual map insert are atomic
     * together - otherwise two concurrent creates could race each other to
     * the same id. Checked against both the in-memory map and the
     * filesystem itself: collisions are astronomically unlikely across the
     * ~900000 possible ids at this app's real scale, but the check is
     * essentially free, so there's no reason to skip it.
     */
    private fun generateSpaceId(): String {
        repeat(50) {
            val candidate = Random.nextInt(100000, 1000000).toString()
            if (!projects.containsKey(candidate) && !spaceDir(candidate).exists()) return candidate
        }
        error("Could not generate a unique 6-digit project id after 50 attempts")
    }

    /**
     * Creates a new project with a fresh 6-digit id, its own on-disk folder,
     * and persisted metadata - returns the created [Project]. [owner] is the
     * creator's email (see [Project.owner]); it is nullable only so that a
     * caller with no signed-in user is a compile-time possibility rather
     * than a special case here, but [ch.arcticsoft.springchat3.web.ProjectController]
     * always passes one.
     */
    fun create(name: String, description: String, owner: String? = null): Project = synchronized(projects) {
        val project = Project(generateSpaceId(), name, description, System.currentTimeMillis(), owner)
        projects[project.spaceId] = project
        persist(project)
        project
    }

    /** All stored projects, oldest first - **unfiltered**; callers that serve a user go through [SpaceAccess.visibleSpaces] instead. */
    fun list(): List<Project> = synchronized(projects) { projects.values.toList() }

    /** One project by id, or null if there's no such space - backs [SpaceAccess]'s own lookups. */
    fun get(spaceId: String): Project? = synchronized(projects) { projects[spaceId] }

    /**
     * Replaces [spaceId]'s share list, returning the updated [Project] or
     * null if there is no such space (2026-08-24, user's own request "owners
     * should be able to add members to their spaces" - see
     * springchat3_multi_user.md in project memory). Whole-list replacement
     * rather than add/remove primitives: the caller
     * ([ch.arcticsoft.springchat3.web.ProjectController]) has already
     * validated the entries, and one write path is easier to keep honest
     * than three.
     *
     * Writes through to `space.json` **and** to the in-memory map, which is
     * what makes a share take effect immediately - editing that file by hand
     * still needs a restart, since [loadPersisted] runs once at startup.
     */
    /**
     * Forgets [spaceId] and moves its whole folder aside, returning false if
     * there is no such space or the move fails (2026-08-24, user's own
     * request "it is not supported to delete a space" - see
     * springchat3_space_deletion.md in project memory).
     *
     * **Moved, not deleted.** `[dataDir]/spaces/<id>` becomes
     * `[dataDir]/trash/<id>-<millis>`, which the app never scans, so the
     * space is gone from its point of view immediately - while a mistake
     * stays recoverable by moving that folder back and restarting. One
     * rename instead of a recursive delete of an entire tree of documents,
     * vector stores and chat sessions, on the first destructive action this
     * app has ever had.
     *
     * The timestamp suffix means deleting, restoring and deleting the same
     * space again cannot collide with its own earlier copy.
     *
     * Removes the in-memory entry only when the move actually succeeded, so
     * a failure leaves the app exactly as it was rather than showing a space
     * that is no longer reachable - or hiding one that is still there.
     * [ch.arcticsoft.springchat3.project.SpaceDeletionService] is what
     * clears everything *else* that knows about the space, and it runs after
     * this returns true.
     */
    fun moveToTrash(spaceId: String): Boolean = synchronized(projects) {
        val dir = spaceDir(spaceId)
        if (!projects.containsKey(spaceId)) return false
        val trash = File(dataDir, "trash")
        trash.mkdirs()
        val target = File(trash, spaceId + "-" + System.currentTimeMillis())
        if (dir.exists() && !dir.renameTo(target)) {
            log.warn("Could not move space {} to {} - leaving it in place", spaceId, target)
            return false
        }
        projects.remove(spaceId)
        log.info("Deleted space {} - its folder is now {}", spaceId, target)
        true
    }

    /**
     * Renames [spaceId], returning the updated [Project] or null if there is
     * no such space (2026-08-25, user's own request "I think we should
     * support to rename a Space").
     *
     * Cheap for one reason worth writing down: **the name is not a key
     * anywhere.** The folder is `spaces/<spaceId>/` and every wire field,
     * every store row and every chat-history entry references the 6-digit
     * id, so nothing else on disk mentions the name and there is nothing to
     * cascade. If that ever stops being true - a name used in a path, or
     * copied into another store - this method is where the cascade belongs.
     */
    fun rename(spaceId: String, name: String): Project? = synchronized(projects) {
        val existing = projects[spaceId] ?: return null
        val updated = existing.copy(name = name)
        projects[spaceId] = updated
        persist(updated)
        updated
    }

    fun updateMembers(spaceId: String, members: List<SpaceMember>): Project? = synchronized(projects) {
        val existing = projects[spaceId] ?: return null
        val updated = existing.copy(members = members)
        projects[spaceId] = updated
        persist(updated)
        updated
    }
}

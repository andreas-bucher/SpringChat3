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
 * One project's identity/metadata (2026-08-23, see
 * springchat3_projects_panel.md in project memory) - [projectId] is a random
 * 6-digit string (e.g. "482913"), not a sequential/database id, per the
 * user's own request; it also doubles as this project's on-disk folder name
 * (see [ProjectStore]'s own doc comment).
 */
data class Project(
    val projectId: String,
    val name: String,
    val description: String,
    val createdAt: Long,
)

/**
 * Store for projects created via
 * [ch.arcticsoft.springchat3.web.ProjectController] (2026-08-23, user's own
 * request "the project shall get a unique 6 digit identifier. a project
 * folder shall be created with the project identifier as folder name. in
 * the project folder the project meta data are stored" - see
 * springchat3_projects_panel.md in project memory): each project gets its
 * own `[dataDir]/projects/<projectId>/project.json`, same per-item-directory
 * persistence pattern as
 * [ch.arcticsoft.springchat3.document.DocumentStore] (one file per item
 * rather than one shared JSON file, so a load/write failure is bounded to a
 * single project) - nested under a `projects/` subdirectory, unlike
 * [DocumentStore]'s flat `[dataDir]/<documentId>/` layout, so a 6-digit
 * project id can never collide with a document's UUID-named directory, and
 * `data/projects/` reads as a real, browsable "project folder" the way the
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

    private fun projectsRoot() = File(dataDir, "projects")

    /**
     * A project's own on-disk folder, `[dataDir]/projects/<projectId>`.
     * Public (2026-08-23, user's own request "when uploading a file or link
     * a google drive folder or link a working document then save the files
     * in the project folder of the active project" - see
     * springchat3_projects_panel.md in project memory) so
     * [ch.arcticsoft.springchat3.document.DocumentStore] can save a
     * project-scoped document's files inside this exact same folder instead
     * of independently recomputing the same path - one place decides where
     * a project's folder lives, not two formulas that could drift apart.
     * Doesn't require [projectId] to be a real, currently-known project -
     * same "pure path function, no existence check" contract [DocumentStore]'s
     * own private `documentDir` already has.
     */
    fun projectDir(projectId: String): File = File(projectsRoot(), projectId)

    private fun projectFile(projectId: String) = File(projectDir(projectId), "project.json")

    private fun loadPersisted(): LinkedHashMap<String, Project> {
        val root = projectsRoot()
        root.mkdirs()
        val loaded = mutableListOf<Project>()
        root.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val file = File(dir, "project.json")
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
        loaded.forEach { map[it.projectId] = it }
        return map
    }

    private fun persist(project: Project) {
        try {
            val dir = projectDir(project.projectId)
            dir.mkdirs()
            objectMapper.writeValue(projectFile(project.projectId), project)
        } catch (e: Exception) {
            log.warn("Could not persist project {} to {}", project.projectId, projectFile(project.projectId), e)
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
    private fun generateProjectId(): String {
        repeat(50) {
            val candidate = Random.nextInt(100000, 1000000).toString()
            if (!projects.containsKey(candidate) && !projectDir(candidate).exists()) return candidate
        }
        error("Could not generate a unique 6-digit project id after 50 attempts")
    }

    /** Creates a new project with a fresh 6-digit id, its own on-disk folder, and persisted metadata - returns the created [Project]. */
    fun create(name: String, description: String): Project = synchronized(projects) {
        val project = Project(generateProjectId(), name, description, System.currentTimeMillis())
        projects[project.projectId] = project
        persist(project)
        project
    }

    /** All stored projects, oldest first - backs the left-hand panel's Projects list. */
    fun list(): List<Project> = synchronized(projects) { projects.values.toList() }
}

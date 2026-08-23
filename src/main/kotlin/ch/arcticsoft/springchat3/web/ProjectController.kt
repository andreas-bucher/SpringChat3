package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.project.Project
import ch.arcticsoft.springchat3.project.ProjectStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * `POST /projects`'s request body - [description] is nullable rather than
 * defaulted (same convention as [ModelOverrideRequest.model]) since only
 * [name] is required; a missing/null value is treated as blank in
 * [ProjectController.create].
 */
data class CreateProjectRequest(val name: String, val description: String?)

/**
 * Backs the left-hand Projects panel's "New Project" popup (2026-08-23, see
 * springchat3_projects_panel.md in project memory) - create + list only for
 * now, matching what was actually asked for; no rename/delete endpoint yet.
 */
@RestController
class ProjectController(
    private val projectStore: ProjectStore,
) {
    /** Every stored project, oldest first - backs the left panel's Projects list on page load. */
    @GetMapping("/projects")
    fun list(): List<Project> = projectStore.list()

    /** `400 Bad Request` if [CreateProjectRequest.name] is blank - [ProjectStore.create] itself trusts its caller and doesn't re-check. */
    @PostMapping("/projects")
    fun create(@RequestBody request: CreateProjectRequest): Project {
        val name = request.name.trim()
        if (name.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is required")
        }
        return projectStore.create(name, request.description?.trim() ?: "")
    }
}

package ch.arcticsoft.springchat3.web

import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Backs the small version label next to the header title in index.html.
 * Reads the Gradle project `version` (build.gradle.kts) at build time via
 * Spring Boot's `springBoot { buildInfo() }` task, which generates
 * META-INF/build-info.properties and makes it available here as an
 * auto-configured [BuildProperties] bean - so the UI always reflects
 * whatever version was actually built into the running jar, rather than a
 * value hand-typed into the page that can drift from reality.
 *
 * [BuildProperties] is nullable here (Kotlin constructor injection treats a
 * nullable parameter type as an optional bean lookup) as a defensive
 * fallback for a build that somehow skipped the buildInfo() task - e.g. a
 * stale IDE run configuration - even though build.gradle.kts wires it to
 * always run ahead of processResources in a normal Gradle build.
 */
@RestController
class VersionController(
    private val buildProperties: BuildProperties?,
) {
    @GetMapping("/version")
    fun version(): Map<String, String> =
        mapOf("version" to (buildProperties?.version ?: "dev"))
}

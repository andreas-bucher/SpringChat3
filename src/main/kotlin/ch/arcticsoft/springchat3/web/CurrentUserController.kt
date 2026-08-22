package ch.arcticsoft.springchat3.web

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * `GET /me`'s response shape - just enough to show who's signed in in the UI
 * (the bottom-left avatar chip and the settings popup's "Signed in as" line,
 * see index.html), not a general profile endpoint. [email] stays nullable
 * even though [ch.arcticsoft.springchat3.security.SecurityConfig]'s allow-list
 * check already rejects a null email before anyone gets this far - no reason
 * to assert non-null here just to shave an "?" off a display-only field.
 */
data class CurrentUserResponse(
    val email: String?,
    val name: String?,
    val picture: String?,
)

/**
 * Backs index.html's signed-in-user avatar chip (2026-08-22, see
 * springchat3_authentication.md in project memory) - every request already
 * requires an authenticated session (see security.SecurityConfig), so this
 * just surfaces who that session belongs to.
 *
 * `@AuthenticationPrincipal` needs the `Mono<OidcUser>` wrapper form here,
 * not a bare `OidcUser` parameter, and the method has to return `Mono` to
 * compose over it - the reactive stack resolves the authenticated principal
 * from `ReactiveSecurityContextHolder` asynchronously, unlike the servlet
 * stack where the plain, unwrapped parameter type works instead. Confirmed
 * against Spring Security's own WebFlux OAuth2 login guidance before writing
 * this, not assumed - same "verify real API against pinned version" habit as
 * the rest of this feature (see SecurityConfig.kt's own doc comments).
 *
 * `OidcUser.email`/`.fullName`/`.picture` are all inherited directly (no
 * need to go through `getUserInfo()`) via `StandardClaimAccessor` - the same
 * confirmed-via-Javadoc pattern `oidcUserService` in SecurityConfig.kt
 * already relies on for `.email`.
 */
@RestController
class CurrentUserController {
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal user: Mono<OidcUser>): Mono<CurrentUserResponse> =
        user.map { CurrentUserResponse(email = it.email, name = it.fullName, picture = it.picture) }
}

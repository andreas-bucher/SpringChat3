package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.security.CURRENT_USER_EMAIL_ATTRIBUTE
import ch.arcticsoft.springchat3.security.CURRENT_USER_IS_GOOGLE_ATTRIBUTE
import ch.arcticsoft.springchat3.security.LocalUserStore
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
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
    /**
     * Whether this session can use the Google Drive features at all
     * (2026-08-24, local accounts - see springchat3_local_accounts.md in
     * project memory). False for a password account: those three actions
     * need a live Google OAuth token, not just an identity, so index.html
     * greys them out in the ＋ popup and explains why.
     *
     * A courtesy for the UI, not the rule -
     * [ch.arcticsoft.springchat3.security.SessionGateWebFilter] refuses
     * every `/drive/` route for such a session regardless of what any client
     * does with this field.
     */
    val canUseGoogleDrive: Boolean = false,
    /**
     * True while a local account is still on the password its admin set, in
     * which case that same filter allows nothing but changing it. index.html
     * turns this into a blocking prompt.
     */
    val mustChangePassword: Boolean = false,
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
class CurrentUserController(
    private val localUserStore: LocalUserStore,
) {
    /**
     * Reads the session rather than the principal (2026-08-24, local
     * accounts): a Google session's principal is an [OidcUser], a local
     * one's is a [org.springframework.security.core.userdetails.UserDetails],
     * and this endpoint has to answer for both. The email and which kind of
     * session it is have already been worked out once, by
     * [ch.arcticsoft.springchat3.security.CurrentUserWebFilter] - so this
     * takes them from there instead of casting the principal a second time
     * and getting it subtly differently.
     *
     * [OidcUser] is still injected for the two things only Google supplies -
     * the full name and the avatar picture. It stays a `Mono` and may be
     * empty for a local session; [Mono.defaultIfEmpty] is what makes this
     * work for both, and dropping it would make `/me` return nothing at all
     * for a password account - which reads, from the browser, exactly like
     * being signed out.
     */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal user: Mono<OidcUser>, exchange: ServerWebExchange): Mono<CurrentUserResponse> {
        val email = exchange.getAttribute<String>(CURRENT_USER_EMAIL_ATTRIBUTE)
        val isGoogle = exchange.getAttribute<Boolean>(CURRENT_USER_IS_GOOGLE_ATTRIBUTE) == true
        val local = if (email == null) null else localUserStore.find(email)
        return user
            .map { CurrentUserResponse(it.email, it.fullName, it.picture, canUseGoogleDrive = true) }
            .defaultIfEmpty(
                CurrentUserResponse(
                    email = email,
                    name = local?.displayName?.ifBlank { null } ?: email,
                    picture = null,
                    canUseGoogleDrive = isGoogle,
                    mustChangePassword = local?.mustChangePassword == true,
                ),
            )
    }
}

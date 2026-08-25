package ch.arcticsoft.springchat3.security

import org.springframework.core.annotation.Order
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * The exchange attribute [CurrentUserWebFilter] writes the signed-in user's
 * email to. Read it through
 * [ch.arcticsoft.springchat3.project.SpaceAccess.currentUserEmail] rather
 * than directly - that one fails closed when it's missing.
 */
const val CURRENT_USER_EMAIL_ATTRIBUTE = "springchat3.currentUserEmail"

/**
 * Where [CurrentUserWebFilter] sits among this app's own [WebFilter]s. Any
 * positive number is comfortably after Spring Security's
 * `WebFilterChainProxy` (registered at -100, far ahead of any application
 * filter), and this one is before [SessionGateWebFilter], which consumes
 * what this filter writes.
 *
 * A plain literal rather than `Ordered.LOWEST_PRECEDENCE - n`: an `@Order`
 * argument has to be a compile-time constant, and a number that obviously is
 * one is worth more here than the symbolic name.
 */
const val CURRENT_USER_FILTER_ORDER = 1000

/**
 * The exchange attribute saying whether this session signed in **through
 * Google** (2026-08-24, local accounts). Not a permission - a capability:
 * only a Google session carries the OAuth token
 * [ch.arcticsoft.springchat3.web.DriveController] needs, so a local account
 * cannot use the Picker, folder linking or Working Documents at all. See
 * [SessionGateWebFilter] for what enforces that, and `GET /me` for how the
 * UI is told.
 */
const val CURRENT_USER_IS_GOOGLE_ATTRIBUTE = "springchat3.currentUserIsGoogle"

/**
 * Puts the signed-in user's email into the request's own attributes, so that
 * every controller can find out who is asking with a plain
 * [ServerWebExchange] parameter (2026-08-24, user's own request "It should
 * be possible that users have their own spaces... some spaces are shared
 * across users" - see springchat3_multi_user.md in project memory).
 *
 * **Why a filter rather than `@AuthenticationPrincipal` on each method.**
 * The reactive stack resolves the principal asynchronously, and this app's
 * one existing use of it
 * ([ch.arcticsoft.springchat3.web.CurrentUserController]) had to take a
 * `Mono<OidcUser>` and return a `Mono` to compose over it. Every controller
 * in this app now needs the caller's identity, and threading a `Mono` into
 * each would have turned a dozen plain `fun list(): List<X>` methods
 * reactive purely for plumbing. `ServerWebExchange` is resolved
 * synchronously as an ordinary handler parameter instead, so the one
 * unavoidable piece of reactive work happens here, once.
 *
 * **Two principal types, one email** (2026-08-24, local accounts - see
 * springchat3_local_accounts.md in project memory). A Google session's
 * principal is an [OidcUser] and the email is a claim on it; a local
 * session's is a [UserDetails] whose username *is* the email (see
 * [LocalUserStore]). [emailOf] handles both, and everything downstream
 * ([ch.arcticsoft.springchat3.project.SpaceAccess], space membership,
 * chat-session ownership) sees the same lowercased string either way and
 * never learns which one it was.
 *
 * This is the one place where adding a sign-in method can quietly break the
 * whole app: if a principal type isn't recognised here, no email attribute
 * is written, and `SpaceAccess` - which fails closed - answers `401` to
 * **every** request rather than to none.
 *
 * Ordering rests on the same fact [SecurityConfig.csrfCookieWebFilter]
 * already relies on: an application [WebFilter] runs *after* Spring
 * Security's own `WebFilterChainProxy` (registered at -100, ahead of
 * anything this app declares) and therefore after the security context has
 * been written into the Reactor context that
 * [ReactiveSecurityContextHolder] reads from.
 *
 * Writes nothing when there is no authenticated user (the context Mono is
 * simply empty) - it does not reject the request itself. Rejecting is
 * [SecurityConfig]'s job, and everything downstream that needs an identity
 * asks [ch.arcticsoft.springchat3.project.SpaceAccess] for it, which
 * responds `401` to a missing attribute. That split is deliberate: a wiring
 * mistake here can only ever deny access, never grant it.
 *
 * **Ordered ahead of [SessionGateWebFilter]**, which reads the attributes
 * this writes. Both would otherwise be `LOWEST_PRECEDENCE` and their
 * relative order undefined - and an undefined order here fails silently in
 * the permissive direction, with the gate simply seeing no user.
 */
@Order(CURRENT_USER_FILTER_ORDER)
@Component
class CurrentUserWebFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        ReactiveSecurityContextHolder.getContext()
            .doOnNext { context ->
                val principal = context.authentication?.principal
                val email = emailOf(principal)
                if (email != null) {
                    exchange.attributes[CURRENT_USER_EMAIL_ATTRIBUTE] = email.lowercase()
                    exchange.attributes[CURRENT_USER_IS_GOOGLE_ATTRIBUTE] = principal is OidcUser
                }
            }
            .then(chain.filter(exchange))

    private fun emailOf(principal: Any?): String? = when (principal) {
        is OidcUser -> principal.email
        // A local account's username IS its email - LocalUserStore builds
        // the UserDetails that way precisely so this stays a one-liner and
        // both sign-in methods produce the same identity.
        is UserDetails -> principal.username
        else -> null
    }
}

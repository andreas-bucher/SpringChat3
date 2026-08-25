package ch.arcticsoft.springchat3.security

import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Two rules that depend on *how* the caller signed in rather than on what
 * they may reach (2026-08-24, local accounts - see
 * springchat3_local_accounts.md in project memory). Both are path-based,
 * which is why they live in a filter rather than in
 * [ch.arcticsoft.springchat3.project.SpaceAccess]: nothing about them
 * concerns a space.
 *
 * **1. Every `/drive/` route needs a Google session.** Each one takes a
 * `@RegisteredOAuth2AuthorizedClient("google")` parameter, and
 * Spring resolves that *before* the handler body runs - so a local account
 * hitting one cannot be turned away by a check inside the method, and would
 * instead produce whatever the resolver does with a session that has no
 * authorized client. A `409 Conflict` here, ahead of the handler, is a
 * definite answer the frontend can show. The UI also greys those actions out
 * (see `GET /me`'s `canUseGoogleDrive`), but that is the courtesy; this is
 * the rule.
 *
 * **2. A local account with [LocalUser.mustChangePassword] can do nothing
 * else first.** An admin-chosen password is a password the admin knows, so
 * it has to be replaced before the session is good for anything. Only the
 * page itself, the identity call, the change endpoint and signing out are
 * allowed through; everything else gets `403` with a message index.html
 * turns into a blocking prompt.
 *
 * Deliberately *not* a redirect: nearly every request this gates is a
 * `fetch()` for JSON, and redirecting those to an HTML page produces a
 * parse error in the browser console rather than anything a user can act
 * on.
 *
 * Ordered after [CurrentUserWebFilter], whose attributes both rules read -
 * see [CURRENT_USER_FILTER_ORDER].
 */
@Order(CURRENT_USER_FILTER_ORDER + 10)
@Component
class SessionGateWebFilter(
    private val localUserStore: LocalUserStore,
) : WebFilter {
    private val log = LoggerFactory.getLogger(SessionGateWebFilter::class.java)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        val email = exchange.getAttribute<String>(CURRENT_USER_EMAIL_ATTRIBUTE)

        if (email != null && path.startsWith(DRIVE_PREFIX) && exchange.getAttribute<Boolean>(CURRENT_USER_IS_GOOGLE_ATTRIBUTE) != true) {
            log.debug("Refusing {} for local account {} - no Google session, so no Drive token", path, email)
            return Mono.error(
                ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Google Drive features need a Google account - this one signs in with a password.",
                ),
            )
        }

        // Only a session that actually signed in with that password. A
        // Google session whose email happens to match a local account is not
        // using that password at all, and blocking it would be a lockout
        // caused by an account it never touched.
        val isLocalSession = exchange.getAttribute<Boolean>(CURRENT_USER_IS_GOOGLE_ATTRIBUTE) != true
        if (email != null && isLocalSession && path !in ALWAYS_ALLOWED &&
            localUserStore.find(email)?.mustChangePassword == true
        ) {
            return Mono.error(
                ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Set your own password before using the app.",
                ),
            )
        }

        return chain.filter(exchange)
    }

    companion object {
        private const val DRIVE_PREFIX = "/drive/"

        /**
         * What a local account still on its admin-set password may reach.
         * `/` and the page itself so the app can load far enough to show the
         * prompt, `/me` because that is what tells it to, the change
         * endpoint itself, and `/logout` so nobody is ever trapped in a
         * session they can't leave.
         */
        private val ALWAYS_ALLOWED = setOf(
            "/",
            "/index.html",
            "/me",
            "/account/password",
            "/logout",
            SecurityConfig.ACCESS_DENIED_PATH,
            SecurityConfig.LOGIN_PATH,
            SecurityConfig.LOGIN_PROCESSING_PATH,
        )
    }
}

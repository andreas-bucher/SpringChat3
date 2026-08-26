package ch.arcticsoft.springchat3.security

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono

/**
 * Google OAuth2/OIDC login, gating the whole app (2026-08-22, user's own
 * request "add oauth2.0 google authentication" - see
 * springchat3_authentication.md in project memory): every request needs an
 * authenticated session, except [ACCESS_DENIED_PATH] itself, the one static
 * page a rejected sign-in is bounced to (see [oidcUserService]). Reactive
 * (WebFlux) config - `@EnableWebFluxSecurity`, not the servlet stack's
 * `@EnableWebSecurity`, to match this app's all-WebFlux stack (see
 * build.gradle.kts - no spring-boot-starter-web anywhere in it).
 *
 * Google's client-id/client-secret come from application.yml's
 * `spring.security.oauth2.client.registration.google.*` (backed by
 * `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` env vars, see .env) - no
 * `provider:` block needed alongside it, since Spring Boot recognizes
 * "google" as one of `CommonOAuth2Provider`'s built-in providers and fills
 * in the authorization/token/user-info endpoints and default scope (openid,
 * profile, email) automatically. Confirmed against Spring Security's own
 * reference docs before writing this, not assumed - this project has a
 * standing "verify real API against pinned version" habit after past
 * guesses going wrong (see e.g. springchat3_document_qa.md's
 * TokenTextSplitter package mixup).
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {
    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    companion object {
        /**
         * The one page a rejected sign-in lands on - must stay `permitAll`
         * (see [securityWebFilterChain]) or an unauthorized email would be
         * bounced straight back into another Google redirect with nothing
         * ever shown to explain why (see [oidcUserService]'s doc comment).
         */
        const val ACCESS_DENIED_PATH = "/access-denied.html"

        /**
         * The sign-in page (2026-08-24, local accounts - see
         * springchat3_local_accounts.md in project memory), offering both
         * "Sign in with Google" and the email/password form.
         *
         * This app deliberately had **no** login page until now: with a
         * single client registration Spring never generates one, and the
         * entry point for an unauthenticated request went straight to
         * `/oauth2/authorization/google`. A second sign-in method means
         * there is now a choice to present, so [securityWebFilterChain]
         * sets this as the entry point explicitly rather than relying on
         * whichever of `formLogin`/`oauth2Login` would otherwise win.
         */
        const val LOGIN_PATH = "/login.html"

        /**
         * Where the login form POSTs. `formLogin()`'s own default
         * processing URL, left as the default on purpose: overriding it
         * through `loginPage(...)` on the reactive DSL also moves the
         * entry-point redirect, which is set explicitly here instead.
         * **If a sign-in POST ever 404s, check this first** - it is the one
         * piece of this file's behaviour that comes from a Spring default
         * rather than from an explicit call.
         */
        const val LOGIN_PROCESSING_PATH = "/login"
    }

    /**
     * Wraps [RedirectServerAuthenticationFailureHandler] with a WARN log of
     * the actual [org.springframework.security.core.AuthenticationException]
     * first - see the doc comment on [securityWebFilterChain]'s
     * `oauth2Login` block for why this exists: without it, every kind of
     * OAuth2 login failure (not just an unauthorized email) silently lands
     * on the exact same "not authorized" page with nothing in the server log
     * to tell them apart.
     */
    private fun loggingAuthenticationFailureHandler(): ServerAuthenticationFailureHandler {
        val redirect = RedirectServerAuthenticationFailureHandler(ACCESS_DENIED_PATH)
        return ServerAuthenticationFailureHandler { webFilterExchange, exception ->
            log.warn("OAuth2 login failed, redirecting to {}: {}", ACCESS_DENIED_PATH, exception.message, exception)
            redirect.onAuthenticationFailure(webFilterExchange, exception)
        }
    }

    /**
     * Forces `access_type=offline&prompt=consent` onto every Google sign-in
     * (2026-08-22, added alongside the Google Drive folder-linking feature -
     * see springchat3_google_drive.md in project memory), the officially
     * documented way to do this on the reactive stack (confirmed against
     * Spring Security's own reference docs before writing this, not
     * assumed): wrap [DefaultServerOAuth2AuthorizationRequestResolver] with
     * [DefaultServerOAuth2AuthorizationRequestResolver.setAuthorizationRequestCustomizer],
     * wired in via `oauth2Login { authorizationRequestResolver(...) }`
     * below - NOT a bare `provider.google.authorization-uri` override in
     * application.yml appending `?access_type=...` to the URL by hand,
     * which isn't a documented mechanism and risks silently mismerging with
     * the query parameters Spring itself builds (client_id, redirect_uri,
     * scope, state, ...) onto that same URI.
     *
     * **Why every sign-in, not just the first:** Google only returns a
     * `refresh_token` when `prompt=consent` forces the consent screen to
     * actually show (a silent/already-consented flow won't reissue one).
     * [DriveController]'s "Sync now" needs that refresh token to keep
     * working past the ~1 hour access-token lifetime - but Spring's default
     * reactive `OAuth2AuthorizedClient` storage
     * (`WebSessionServerOAuth2AuthorizedClientRepository`) is
     * session-scoped, not durably persisted across sessions, so a returning
     * user's *new* session needs its *own* freshly-issued refresh token, not
     * just whichever session happened to be first.
     */
    private fun offlineAccessAuthorizationRequestResolver(
        clientRegistrationRepository: ReactiveClientRegistrationRepository,
    ): ServerOAuth2AuthorizationRequestResolver {
        val resolver = DefaultServerOAuth2AuthorizationRequestResolver(clientRegistrationRepository)
        resolver.setAuthorizationRequestCustomizer { builder ->
            builder.additionalParameters { params ->
                params["access_type"] = "offline"
                params["prompt"] = "consent"
            }
        }
        return resolver
    }

    /**
     * Hashes and verifies local account passwords. The *delegating* encoder
     * rather than a bare [org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder]:
     * it stores each hash with its algorithm as a `{bcrypt}` prefix, so a
     * later move to a different algorithm can re-hash on next sign-in
     * instead of invalidating every stored password at once. Nothing new on
     * the classpath - `spring-boot-starter-oauth2-client` already brings
     * spring-security-crypto in.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    /**
     * Looks a local account up for the sign-in form (2026-08-24 - see
     * [UserStore]). Declaring this bean is all that is needed to make
     * `formLogin` work: Spring Boot's reactive security autoconfiguration
     * builds a `UserDetailsRepositoryReactiveAuthenticationManager` around a
     * [ReactiveUserDetailsService] bean when one exists, using the
     * [PasswordEncoder] bean above - and stops autoconfiguring its own
     * random-password default user, which is the behaviour to notice if
     * this bean is ever removed.
     *
     * Unknown email means [Mono.empty], not an error: Spring turns that into
     * the same "bad credentials" the wrong password produces, so the form
     * cannot be used to find out which addresses have accounts.
     *
     * [KnownUsers.isAllowedGoogleEmail] is deliberately NOT consulted here.
     * That list gates *Google* sign-in; `users.json` is the gate for a local
     * account, so each mechanism's roster lives where you would go looking
     * for it.
     */
    @Bean
    fun localUserDetailsService(userStore: UserStore): ReactiveUserDetailsService =
        ReactiveUserDetailsService { username ->
            val user = userStore.find(username)
            val hash = user?.passwordHash
            if (user == null || hash.isNullOrBlank()) {
                Mono.empty()
            } else {
                Mono.just(
                    User.withUsername(user.email)
                        .password(hash)
                        .disabled(user.disabled)
                        .roles("USER")
                        .build(),
                )
            }
        }

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        clientRegistrationRepository: ReactiveClientRegistrationRepository,
    ): SecurityWebFilterChain {
        http
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers(ACCESS_DENIED_PATH, LOGIN_PATH, LOGIN_PROCESSING_PATH).permitAll()
                    .anyExchange().authenticated()
            }
            .exceptionHandling { exceptions ->
                // Explicit, not inherited: with both formLogin and
                // oauth2Login configured, which one's entry point handles an
                // unauthenticated request is a question this app should not
                // have to answer by experiment. Everyone lands on the one
                // page that offers both.
                exceptions.authenticationEntryPoint(RedirectServerAuthenticationEntryPoint(LOGIN_PATH))
            }
            .formLogin { form ->
                // A failed local sign-in returns to the form with ?error,
                // NOT to ACCESS_DENIED_PATH: a wrong password is something
                // to try again, unlike an unauthorized Google account, which
                // will never succeed no matter how often it is retried.
                form.authenticationFailureHandler(
                    RedirectServerAuthenticationFailureHandler("$LOGIN_PATH?error"),
                )
            }
            .oauth2Login { oauth2 ->
                oauth2.authorizationRequestResolver(offlineAccessAuthorizationRequestResolver(clientRegistrationRepository))
                // Default failure handler redirects to "/login?error",
                // which is NOT where a rejected Google account belongs:
                // /login is the local form's POST target (see
                // LOGIN_PROCESSING_PATH), and an unauthorized Google email
                // is not something retrying the form can fix. Before local
                // accounts existed there was no /login at all and the
                // default bounced into an endless redirect back to Google,
                // with nothing ever telling the user why they couldn't get
                // in - which is how this override came to be written.
                //
                // loggingAuthenticationFailureHandler wraps the redirect, not
                // replaces it: this one handler fires for EVERY oauth2Login
                // failure, not just oidcUserService's own allow-list
                // rejection - a token-exchange error, an ID token validation
                // failure, or the app being unable to reach Google at all
                // would ALSO land here and show the same "not authorized"
                // page, which is actively misleading for those cases. Found
                // this the hard way (2026-08-22, see
                // springchat3_authentication.md in project memory): a real
                // rejection landed here with nothing logged from
                // oidcUserService at all, meaning the failure happened
                // earlier in the exchange - impossible to diagnose with only
                // a silent redirect and no visibility into why.
                oauth2.authenticationFailureHandler(loggingAuthenticationFailureHandler())
            }
            .csrf { csrf ->
                // Cookie-based CSRF (not the default WebSession-backed
                // repository) so index.html's plain fetch()-based JS can
                // read the token itself and echo it back as a header - see
                // csrfCookieWebFilter below for why a second bean is also
                // needed to make that cookie actually appear. The plain
                // (non-Xor) handler, not the 6.0+ default
                // XorServerCsrfTokenRequestAttributeHandler, is required
                // here: the Xor handler's BREACH-protection masking is only
                // ever unmasked server-side when a token is rendered into a
                // server-side HTML form/model attribute - a value this app's
                // JS never sees, since it reads the token straight from the
                // cookie the repository writes (always the raw value).
                // Sending that raw cookie value back as the X-XSRF-TOKEN
                // header against the Xor handler would fail every single
                // request's CSRF check. Confirmed against Spring Security's
                // own SPA guidance before writing this - not the kind of
                // thing worth guessing on for a check that gates every
                // POST/DELETE in the app.
                csrf.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                csrf.csrfTokenRequestHandler(ServerCsrfTokenRequestAttributeHandler())
            }
        return http.build()
    }

    /**
     * The CSRF token exchange attribute is a lazily-subscribed `Mono` by
     * design - nothing forces it to actually run (and so nothing writes the
     * XSRF-TOKEN cookie the repository above is configured for) unless
     * something downstream subscribes, e.g. a server-rendered view
     * referencing it. This app has no server-rendered views at all (it's a
     * static index.html plus a JSON/NDJSON API), so without this filter the
     * cookie would simply never get set on any response - a well-documented
     * reactive-CSRF gotcha (see Spring Security's own reactive CSRF
     * reference docs), not specific to this app.
     */
    @Bean
    fun csrfCookieWebFilter(): WebFilter = WebFilter { exchange, chain ->
        val csrfToken = exchange.getAttribute<Mono<CsrfToken>>(CsrfToken::class.java.name)
        (csrfToken ?: Mono.empty()).then(chain.filter(exchange))
    }

    /**
     * Restricts sign-in to the allow-list (`springchat3.allowed-emails`,
     * comma-separated - see application.yml/.env), rejecting anyone else via
     * an [OAuth2AuthenticationException] rather than letting them in.
     *
     * The list itself is parsed by [KnownUsers] (2026-08-24) rather than
     * here, so that this check and the "can this person actually accept a
     * share?" check in
     * [ch.arcticsoft.springchat3.web.ProjectController.addMember] read the
     * same set. Injected as a **method** parameter, not through this class's
     * constructor: [KnownUsers] needs [UserStore], which needs the
     * [passwordEncoder] bean declared above, and a constructor dependency
     * would close that into a cycle Spring could not resolve at startup.
     *
     * Wraps [OidcReactiveOAuth2UserService] specifically - not the plain
     * `DefaultReactiveOAuth2UserService` - because Google's registration
     * requests the "openid" scope by default (via `CommonOAuth2Provider`),
     * so Google authenticates via OpenID Connect, not bare OAuth2. Getting
     * this wrapper type wrong would be a silent security bug, not a compile
     * error: Spring routes an OIDC provider's user-loading through a
     * `ReactiveOAuth2UserService<OidcUserRequest, OidcUser>`, so a bean of
     * the plain OAuth2 type would simply never run, leaving this allow-list
     * entirely unenforced with nothing to notice.
     *
     * No manual wiring call is needed on `oauth2Login()`'s reactive DSL -
     * unlike the servlet stack's `userInfoEndpoint().oidcUserService(...)`
     * chain, reactive's `OAuth2LoginSpec` has no equivalent method at all
     * (confirmed against its own API docs before relying on plain `@Bean`
     * discovery instead). Spring Boot's reactive OAuth2 client
     * autoconfiguration looks up a bean of this exact generic type from the
     * application context and substitutes it for the default when present -
     * confirmed against Spring Security's reference docs before trusting a
     * bare `@Bean` to silently do the right thing here.
     */
    @Bean
    fun oidcUserService(knownUsers: KnownUsers): ReactiveOAuth2UserService<OidcUserRequest, OidcUser> {
        val delegate = OidcReactiveOAuth2UserService()
        return ReactiveOAuth2UserService { request ->
            delegate.loadUser(request).flatMap { oidcUser ->
                val email = oidcUser.email?.lowercase()
                if (knownUsers.isAllowedGoogleEmail(email)) {
                    Mono.just(oidcUser)
                } else {
                    log.warn("Rejected Google sign-in from unauthorized email: {}", email ?: "<none>")
                    Mono.error(
                        OAuth2AuthenticationException(
                            OAuth2Error("unauthorized_email", "This Google account is not authorized for this app.", null),
                        ),
                    )
                }
            }
        }
    }
}

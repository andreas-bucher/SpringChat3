package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.agent.ChatProgressBus
import ch.arcticsoft.springchat3.agent.ChatProgressEvent
import ch.arcticsoft.springchat3.agent.ChatReply
import ch.arcticsoft.springchat3.agent.ChatRequest
import ch.arcticsoft.springchat3.chat.ChatHistoryStore
import ch.arcticsoft.springchat3.project.SpaceAccess
import ch.arcticsoft.springchat3.settings.SettingsResolver
import com.embabel.agent.api.invocation.AgentInvocation
import com.embabel.agent.core.AgentPlatform
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.UUID

/**
 * Reactive (WebFlux) entry point into the agent platform. The Embabel
 * invocation itself is currently a blocking call, so it's shifted onto the
 * bounded-elastic scheduler rather than run on a Netty event-loop thread.
 *
 * NOTE: AgentInvocation's exact builder API can shift between Embabel
 * releases - if this doesn't compile against the pinned version, check
 * https://docs.embabel.com/embabel-agent/guide/ for the current signature.
 */
@RestController
class ChatController(
    private val agentPlatform: AgentPlatform,
    private val progressBus: ChatProgressBus,
    private val chatHistoryStore: ChatHistoryStore,
    private val spaceAccess: SpaceAccess,
    private val settingsResolver: SettingsResolver,
) {

    private val log = LoggerFactory.getLogger(ChatController::class.java)

    @PostMapping(
        "/chat",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun chat(@RequestBody request: ChatRequest, exchange: ServerWebExchange): Mono<ChatReply> {
        log.info("*************************************************************")
        log.info("Chat message: {}", request.message)
        val email = spaceAccess.currentUserEmail(exchange)
        return invoke(authorize(request, exchange, email), email)
    }

    @PostMapping(
        "/chat/stream",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_NDJSON_VALUE],
    )
    fun chatStream(
        @RequestBody request: ChatRequest,
        response: ServerHttpResponse,
        exchange: ServerWebExchange,
    ): Flux<ChatProgressEvent> {
        // Tells nginx not to buffer this response (2026-08-23, user's own
        // report: the live "Document search strategy (granite4.1:3b) ..."
        // rows appear when the app is reached on localhost but not through
        // the nginx reverse proxy). nginx buffers a proxied response by
        // default, so every progress line was being held back and delivered
        // in one burst when the turn finished - the stream still worked, it
        // just stopped being live, which looks exactly like the feature
        // being broken.
        //
        // deploy/nginx/springchat3.arcticsoft.ch.conf turns buffering off for
        // this location too. Both exist deliberately: the config is the real
        // fix on the host we control, and this header is what makes the
        // stream survive a proxy whose config we do not control (or an nginx
        // whose deployed conf has drifted from the one in this repo - the
        // deployed copy is edited in place by certbot). Ignored by every
        // other proxy and by a direct connection.
        response.headers.add("X-Accel-Buffering", "no")

        log.info("*************************************************************")
        log.info("Chat message (streamed): {}", request.message)

        // Authorized before the progress sink is opened, so a rejected
        // request fails as a plain error response rather than as an NDJSON
        // stream carrying a single Failed event.
        val email = spaceAccess.currentUserEmail(exchange)
        val authorized = authorize(request, exchange, email)
        val correlationId = UUID.randomUUID().toString()
        val sink = progressBus.open(correlationId)

        invoke(authorized.copy(correlationId = correlationId), email)
            .subscribe(
                {
                    // Success: ChatAgent.answer already emitted Done onto
                    // this same sink before returning, so there's nothing
                    // left to push here - just let the stream end.
                    sink.tryEmitComplete()
                },
                { error ->
                    log.warn("Chat invocation failed", error)
                    sink.tryEmitNext(ChatProgressEvent.Failed(error.message ?: "Something went wrong"))
                    sink.tryEmitComplete()
                },
            )

        return sink.asFlux().doFinally { progressBus.close(correlationId) }
    }

    /**
     * Checks the caller may use [ChatRequest.spaceId] at all, then stamps
     * everything about this turn that is the server's decision rather than
     * the client's: whether they may *change* anything in that space
     * (2026-08-24, see springchat3_multi_user.md in project memory) and,
     * since 2026-08-25, which settings apply to them (see
     * springchat3_settings.md - the agent is a singleton, so this is the only
     * point at which "who is asking" still exists).
     *
     * **All three are overwritten unconditionally**, never merged with
     * whatever arrived in the request body: each is a server-side decision on
     * a field the client can also send, so the only safe handling is to
     * ignore what came in. A viewer therefore gets a chat agent that can read
     * and answer but not edit a Word document - which is what "view-only" has
     * to mean once the agent has edit tools of its own (see
     * [ch.arcticsoft.springchat3.agent.ChatAgent.documentEdit]) - and nobody
     * can select a model the allow-list forbids, or unlock a document for
     * editing, by hand-writing a request.
     */
    private fun authorize(request: ChatRequest, exchange: ServerWebExchange, email: String): ChatRequest {
        spaceAccess.requireRead(exchange, request.spaceId)
        return request.copy(
            documentEditingAllowed = spaceAccess.canWrite(exchange, request.spaceId),
            toolsEnabled = settingsResolver.toolsEnabledFor(email),
            modelOverrides = settingsResolver.effectiveOverrides(email),
            editableDocumentIds = settingsResolver.editableDocumentIdsFor(email),
        )
    }

    private fun invoke(request: ChatRequest, email: String): Mono<ChatReply> =
        Mono.fromCallable {
            log.debug("invoke...")
            AgentInvocation.builder(agentPlatform)
                .build(ChatReply::class.java)
                .invoke(request)
        }.subscribeOn(Schedulers.boundedElastic())
            // Captures this turn for the left panel's per-project "Chats"
            // history (2026-08-23, see springchat3_projects_panel.md in
            // project memory) - one shared call site for both /chat and
            // /chat/stream, since both route through this same private
            // helper. Runs only on success (doOnNext), same as ChatAgent's
            // own Done event this Mono resolves alongside (see
            // ChatProgressEvent.Done's own doc comment) - a failed turn has
            // no assistant reply worth recording. request.sessionId is
            // passed through so the turn lands in the right session file
            // (2026-08-23, see ChatRequest.sessionId's own doc comment).
            .doOnNext { reply ->
                chatHistoryStore.recordTurn(request.sessionId, request.spaceId, request.message, reply.text, email)
            }
}

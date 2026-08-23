package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.agent.ChatProgressBus
import ch.arcticsoft.springchat3.agent.ChatProgressEvent
import ch.arcticsoft.springchat3.agent.ChatReply
import ch.arcticsoft.springchat3.agent.ChatRequest
import ch.arcticsoft.springchat3.chat.ChatHistoryStore
import com.embabel.agent.api.invocation.AgentInvocation
import com.embabel.agent.core.AgentPlatform
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
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
) {

    private val log = LoggerFactory.getLogger(ChatController::class.java)

    @PostMapping(
        "/chat",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun chat(@RequestBody request: ChatRequest): Mono<ChatReply> {
        log.info("*************************************************************")
        log.info("Chat message: {}", request.message)
        return invoke(request)
    }

    @PostMapping(
        "/chat/stream",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_NDJSON_VALUE],
    )
    fun chatStream(@RequestBody request: ChatRequest): Flux<ChatProgressEvent> {
        log.info("*************************************************************")
        log.info("Chat message (streamed): {}", request.message)

        val correlationId = UUID.randomUUID().toString()
        val sink = progressBus.open(correlationId)

        invoke(request.copy(correlationId = correlationId))
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

    private fun invoke(request: ChatRequest): Mono<ChatReply> =
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
                chatHistoryStore.recordTurn(request.sessionId, request.projectId, request.message, reply.text)
            }
}

package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.chat.ChatHistoryEntry
import ch.arcticsoft.springchat3.chat.ChatHistoryStore
import ch.arcticsoft.springchat3.project.SpaceAccess
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

/**
 * Backs the left-hand Projects panel's "Chats" section (2026-08-23, user's
 * own request "the chat history shall be by project. can you capture the
 * chat history and enlist below the project name." - see
 * springchat3_projects_panel.md in project memory) - list only, same
 * read-only split [ch.arcticsoft.springchat3.web.WebPageController] doesn't
 * have to make (that one both writes and reads); capturing a turn happens in
 * [ChatController] via [ChatHistoryStore.recordTurn], not here.
 *
 * Read-only until 2026-08-28, when [delete] gave the panel's rows a x -
 * still not a write path in the space sense (nothing about the space
 * changes), which is exactly why it asks for no write access.
 */
@RestController
class ChatHistoryController(
    private val chatHistoryStore: ChatHistoryStore,
    private val spaceAccess: SpaceAccess,
) {
    /**
     * The caller's own chat messages, in the spaces they can see -
     * index.html groups/filters by `spaceId` and `sessionId` itself.
     * Two filters, not one: a shared space shows its documents to every
     * member but each member's chats only to themselves (2026-08-24, see
     * springchat3_multi_user.md in project memory).
     */
    @GetMapping("/chat-history")
    fun list(exchange: ServerWebExchange): List<ChatHistoryEntry> =
        chatHistoryStore.getAll(spaceAccess.currentUserEmail(exchange), spaceAccess.visibleSpaceIds(exchange))

    /**
     * Removes one whole session (2026-08-28, user's own request "Enable to
     * remove chat history").
     *
     * The two filters are the same pair [list] passes, and passing them is
     * the whole access check: the store looks for the session only in the
     * spaces the caller can see and only accepts one that is theirs, so
     * there is no space to `requireRead` here that the store hasn't already
     * had to identify itself (see [ChatHistoryStore.delete]).
     *
     * `404` covers all three of "no such session", "not in a space you can
     * see" and "not yours", deliberately - a `403` for the last two would
     * confirm that someone else's session id is real.
     */
    @DeleteMapping("/chat-history/{sessionId}")
    fun delete(@PathVariable sessionId: String, exchange: ServerWebExchange) {
        val removed = chatHistoryStore.delete(
            sessionId,
            spaceAccess.currentUserEmail(exchange),
            spaceAccess.visibleSpaceIds(exchange),
        )
        if (!removed) throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such chat session")
    }
}

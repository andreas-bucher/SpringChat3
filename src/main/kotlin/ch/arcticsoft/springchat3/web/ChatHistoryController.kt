package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.chat.ChatHistoryEntry
import ch.arcticsoft.springchat3.chat.ChatHistoryStore
import ch.arcticsoft.springchat3.project.SpaceAccess
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * Backs the left-hand Projects panel's "Chats" section (2026-08-23, user's
 * own request "the chat history shall be by project. can you capture the
 * chat history and enlist below the project name." - see
 * springchat3_projects_panel.md in project memory) - list only, same
 * read-only split [ch.arcticsoft.springchat3.web.WebPageController] doesn't
 * have to make (that one both writes and reads); capturing a turn happens in
 * [ChatController] via [ChatHistoryStore.recordTurn], not here.
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
}

package ch.arcticsoft.springchat3.web

import ch.arcticsoft.springchat3.chat.ChatHistoryEntry
import ch.arcticsoft.springchat3.chat.ChatHistoryStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

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
) {
    /** Every captured chat message across every project - index.html groups/filters by `projectId` and `sessionId` itself. */
    @GetMapping("/chat-history")
    fun list(): List<ChatHistoryEntry> = chatHistoryStore.getAll()
}

package ch.arcticsoft.springchat3.chat

import ch.arcticsoft.springchat3.project.ProjectStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.UUID

/**
 * One captured chat message - either the user's own question or the
 * assistant's reply to it - for the left panel's per-project "Chats"
 * history (2026-08-23, user's own request "the chat history shall be by
 * project. can you capture the chat history and enlist below the project
 * name." - see springchat3_projects_panel.md in project memory).
 *
 * A turn (one user message + the assistant's reply to it) is stored as two
 * sibling entries sharing the same [turnId] rather than a single combined
 * "question+answer" record - index.html's `renderChatHistory` only ever
 * renders `role == "user"` entries today, but keeping the assistant's own
 * reply as an equally-addressable entry (not folded into the user entry, or
 * dropped entirely) means a future transcript/resume view can read either
 * half independently without a store migration.
 *
 * [sessionId] groups every turn that belongs to the same ongoing
 * conversation (2026-08-23, user's own request "add a chat sessionId to the
 * chat-history, append questions and answer to the same chat session. only
 * when new chat sessions is create, start with new chat-history, save in new
 * file." - see springchat3_projects_panel.md in project memory). It's
 * index.html's own `activeSessionId` at send time - a fresh
 * `crypto.randomUUID()` minted once when the page first loads, or again
 * whenever the user clicks "New Chat" - never re-derived here. Per the
 * user's own scoping answer this turn ("storage only for now"), nothing in
 * this app currently lists or browses sessions by this id; it exists purely
 * so [recordTurn] can decide which file on disk a turn belongs to (see this
 * class's own doc comment) and so a future session-list UI has the field
 * ready to key off without another storage migration. Duplicates
 * [ChatSessionFile.sessionId] (the file this entry lives inside already
 * carries the same id at its top level, see that class's own doc comment) -
 * kept on every entry too, not just the wrapper, so a single
 * [ChatHistoryEntry] read out of [getAll]'s flattened list (which drops the
 * wrapper) is still self-describing.
 *
 * [spaceId] is whichever project was active in the browser at the moment
 * the message was sent ([ch.arcticsoft.springchat3.agent.ChatRequest.spaceId],
 * itself just index.html's `activeProjectId` at send time) - fixed at
 * capture time and never moved afterward, the same convention every other
 * `spaceId` field in this app already follows (e.g.
 * [ch.arcticsoft.springchat3.document.ExtractedDocument.spaceId]). Null
 * only for a turn sent before any project exists yet.
 *
 * [sessionName] is NOT persisted as part of this entry on disk - a session's
 * name lives once, at the top of its own [ChatSessionFile] wrapper, not
 * repeated on every entry inside it (see that class's own doc comment).
 * [getAll] copies the owning session's name onto every entry it returns
 * (2026-08-23, user's own request "On the left panel on the chat history,
 * enlist the sessions once and not all entries of the session... the whole
 * chat session should be displayed" - see springchat3_projects_panel.md in
 * project memory) purely so index.html's left-panel Chats section can group
 * `GET /chat-history`'s flat entry list by session and label each group
 * without a second round-trip - defaults to blank for any entry read
 * straight off disk before that enrichment happens.
 */
data class ChatHistoryEntry(
    val entryId: String,
    val turnId: String,
    val sessionId: String,
    val spaceId: String?,
    val role: String,
    val text: String,
    val timestamp: Long,
    val sessionName: String = "",
)

/**
 * The actual on-disk shape of one session file - a top-level wrapper around
 * its [entries] carrying the session's own identity, rather than a bare JSON
 * array of [ChatHistoryEntry] the way this file used to be written
 * (2026-08-23, user's own request "when new session chat-history is create.
 * add to the json a top level element with the sessionid and session name."
 * - see springchat3_projects_panel.md in project memory).
 *
 * [sessionName] is computed once, from the very first user message ever
 * recorded in this session, the moment the session's file is first created
 * (see [ChatHistoryStore.recordTurn]) - never recomputed afterward even
 * though later turns in the same session obviously exist too, the same
 * "fixed at creation time, never re-read later" convention every other
 * derived-at-creation field in this app already follows (e.g.
 * [ChatHistoryEntry.spaceId] itself). Read by [ChatHistoryStore.getAll]
 * (2026-08-23, user's own request "enlist the sessions once and not all
 * entries of the session" - see springchat3_projects_panel.md in project
 * memory) and copied onto every [ChatHistoryEntry] it flattens out of this
 * wrapper, so index.html's left-panel Chats section can label one row per
 * session with it.
 */
data class ChatSessionFile(
    val sessionId: String,
    val sessionName: String,
    val entries: List<ChatHistoryEntry>,
    /**
     * Whose session this is - the email of whoever sent its first turn
     * (2026-08-24, agreed alongside shared spaces: "only your own" chats,
     * see springchat3_multi_user.md in project memory). Fixed when the file
     * is created and never rewritten, so a session stays with the person who
     * started it even if the space it lives in is later shared.
     *
     * Null for every session recorded before this field existed; [getAll]
     * shows those to everyone, the same "a null owner means everyone, not
     * nobody" rule [ch.arcticsoft.springchat3.project.Project.owner] uses.
     */
    val owner: String? = null,
)

/**
 * Persists every captured chat message under the *owning project's own
 * folder*, one file per chat session -
 * `[dataDir]/spaces/<spaceId>/sessions/<sessionId>.json` - reusing
 * [ProjectStore.spaceDir] rather than recomputing that path independently
 * (same "one place decides where a project's folder lives" reasoning
 * [ch.arcticsoft.springchat3.document.DocumentStore] already follows for
 * project-scoped documents - see that class's own doc comment).
 *
 * **Restructured 2026-08-23 from one file per project**
 * (`[dataDir]/spaces/<spaceId>/chat-history.json`, holding every session
 * mixed together) **to one file per session within a project**, each file a
 * bare JSON array of [ChatHistoryEntry] (user's own request "add a chat
 * sessionId to the chat-history, append questions and answer to the same
 * chat session. only when new chat sessions is create, start with new
 * chat-history, save in new file."), **then restructured again the same
 * day** so each session file is a [ChatSessionFile] wrapper carrying the
 * session's id and a derived [ChatSessionFile.sessionName] at its top level
 * instead of a bare array (user's own follow-up request "when new session
 * chat-history is create. add to the json a top level element with the
 * sessionid and session name. session name can be first 20 characters +
 * '...' of the first question" - see springchat3_projects_panel.md in
 * project memory for both). A turn is a read-modify-write against just its
 * own session's file, and [getAll] reads every session file it finds under
 * a project's `sessions/` folder (plus the unassigned fallback folder
 * below) and flattens their [ChatSessionFile.entries] into one list - the
 * `GET /chat-history` read contract itself is unchanged by either
 * restructure, still one flat list of [ChatHistoryEntry] across every
 * project ([ch.arcticsoft.springchat3.web.ChatHistoryController] and
 * index.html's own fetch-everything-once/group-in-JS convention are both
 * untouched).
 *
 * Deliberately **no in-memory cache** (the original per-project design kept
 * one in a `ConcurrentHashMap`) - this app's real scale is a single user
 * with, at most, a handful of sessions per project, so reading a session's
 * file fresh on every [recordTurn] call and re-listing a project's
 * `sessions/` directory fresh on every [getAll] call is simpler and can't
 * ever go stale against another process/session writing the same files, at
 * the cost of a bit more disk I/O than caching would - not worth the added
 * complexity at this app's scale (same "not worth it yet" precedent this
 * app's other stores already follow - see springchat3_document_qa.md in
 * project memory).
 *
 * A turn recorded with no project at all (sent before any project exists
 * yet - see [ChatHistoryEntry.spaceId]'s own doc comment) has no project
 * folder to live in, so its session file lives in one small fallback
 * directory instead, `[dataDir]/chat-history-unassigned/<sessionId>.json`.
 *
 * **Deliberately not migrated** from either prior layout - same "not worth
 * one-time migration code at this app's real scale so far" precedent this
 * app's other storage-layout changes already follow (see
 * springchat3_document_qa.md/springchat3_projects_panel.md in project
 * memory) - any session file written as a bare array by the previous
 * restructure fails [readSession]'s parse (logged, then treated as empty,
 * same as a missing file) rather than crashing, but its turns are
 * effectively orphaned, same as every earlier storage-layout change in this
 * app. No delete/prune support either (yet) - same "no cleanup code until
 * someone actually asks for it" precedent.
 */
@Component
class ChatHistoryStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
    private val projectStore: ProjectStore,
) {
    private val log = LoggerFactory.getLogger(ChatHistoryStore::class.java)
    private val objectMapper = jacksonObjectMapper()

    private fun sessionsDir(key: String): File =
        if (key == UNASSIGNED_KEY) File(dataDir, "chat-history-unassigned") else File(projectStore.spaceDir(key), "sessions")

    private fun sessionFile(key: String, sessionId: String): File = File(sessionsDir(key), "$sessionId.json")

    /**
     * A blank/missing session id (a caller that predates the sessionId
     * restructure, or a raw request that never set one) is folded into one
     * shared "unsessioned" file per project rather than rejected or given a
     * fresh random id per call - the latter would silently split every such
     * turn into its own single-turn "session", which is worse than just
     * grouping them together the way the pre-session-id design implicitly
     * did.
     */
    private fun normalizeSessionId(sessionId: String): String = sessionId.ifBlank { "unsessioned" }

    /**
     * The session's display name, derived from [firstQuestion] - the very
     * first user message recorded in the session - per the user's own
     * spec: "first 20 characters + '...' of the first question". Applied
     * literally only when there's actually something to truncate; a
     * question already 20 characters or shorter is kept as-is rather than
     * always tacking on a trailing "..." that would misleadingly suggest
     * more text was cut off than really was.
     */
    private fun sessionNameFrom(firstQuestion: String): String =
        if (firstQuestion.length > SESSION_NAME_PREVIEW_LENGTH) {
            firstQuestion.take(SESSION_NAME_PREVIEW_LENGTH) + "..."
        } else {
            firstQuestion
        }

    private fun readSession(file: File): ChatSessionFile? {
        if (!file.exists()) return null
        return try {
            objectMapper.readValue<ChatSessionFile>(file)
        } catch (e: Exception) {
            log.warn("Could not load persisted chat session from {} - starting with none", file, e)
            null
        }
    }

    /**
     * Every entry in [key]'s sessions that [email] may see - their own, plus
     * any session with no owner at all (recorded before sessions had one).
     */
    private fun allEntriesFor(key: String, email: String): List<ChatHistoryEntry> {
        val files = sessionsDir(key).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.sortedBy { it.name }
            .mapNotNull { readSession(it) }
            .filter { it.owner == null || it.owner.equals(email, ignoreCase = true) }
            .flatMap { session -> session.entries.map { it.copy(sessionName = session.sessionName) } }
    }

    /**
     * Every captured chat message [email] may see - **their own sessions**
     * (plus any ownerless one, see [ChatSessionFile.owner]) in the spaces
     * [visibleSpaceIds] lists - grouped by project in [ProjectStore.list]'s
     * own oldest-created-first order, unassigned (no-project) entries last -
     * backs `GET /chat-history`.
     *
     * Both filters are the caller's to supply rather than this store's to
     * work out, since
     * [ch.arcticsoft.springchat3.project.SpaceAccess] is the one place that
     * decides visibility (2026-08-24, see springchat3_multi_user.md in
     * project memory). Unassigned entries are deliberately outside the
     * space filter: they belong to no space, and are readable by everyone
     * under the same legacy rule.
     *
     * Each [ChatSessionFile] wrapper is unwrapped down to
     * its [ChatSessionFile.entries] here, with [ChatSessionFile.sessionName]
     * copied onto every one of them (see [ChatHistoryEntry.sessionName]'s own
     * doc comment) so index.html can group this flat list by `sessionId` and
     * label each group without a second call. index.html groups/filters this
     * flat list by `spaceId` and `sessionId` itself, unchanged by either of
     * this class's 2026-08-23 restructures (see this class's own doc
     * comment).
     */
    fun getAll(email: String, visibleSpaceIds: Set<String>): List<ChatHistoryEntry> =
        projectStore.list()
            .filter { it.spaceId in visibleSpaceIds }
            .flatMap { allEntriesFor(it.spaceId, email) } + allEntriesFor(UNASSIGNED_KEY, email)

    /**
     * Records one completed turn - [userMessage] and the assistant's
     * [assistantText] reply to it - as two sibling [ChatHistoryEntry] rows
     * sharing a fresh [ChatHistoryEntry.turnId], both tagged with
     * [sessionId] and [spaceId], read-modify-write against that one
     * session's own file (`sessions/<sessionId>.json` under the project, or
     * the unassigned fallback folder when [spaceId] is null - see this
     * class's own doc comment). The one write path
     * [ch.arcticsoft.springchat3.web.ChatController] calls once per
     * completed turn, from the single `invoke(...)` helper shared by both
     * `/chat` and `/chat/stream` (see that class's own doc comment) - so
     * both endpoints capture history identically without duplicating this
     * call at each of their two call sites.
     *
     * When the session's file doesn't exist yet, this call is what creates
     * it - [userMessage] on *this* call is therefore the session's first
     * question, so [ChatSessionFile.sessionName] is derived from it right
     * here (via [sessionNameFrom]) and never recomputed on any later call
     * for the same session, even though [userMessage] is different every
     * time - see [ChatSessionFile.sessionName]'s own doc comment.
     *
     * `@Synchronized` here is coarser than strictly necessary (blocks a
     * concurrent write to a *different* session's or project's file too,
     * not just the same one) but matches this store's own prior single-lock
     * behavior and this app's real scale (single user) - not worth a
     * per-session lock map for that.
     */
    @Synchronized
    fun recordTurn(sessionId: String, spaceId: String?, userMessage: String, assistantText: String, owner: String?) {
        val effectiveSessionId = normalizeSessionId(sessionId)
        val turnId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val key = spaceId ?: UNASSIGNED_KEY
        val file = sessionFile(key, effectiveSessionId)
        val existing = readSession(file)
        val sessionName = existing?.sessionName ?: sessionNameFrom(userMessage)
        // An existing session keeps its original owner: whoever started it
        // owns it, even if someone else could reach the same file.
        val sessionOwner = existing?.owner ?: owner
        val updatedEntries = (existing?.entries ?: emptyList()) +
            ChatHistoryEntry(UUID.randomUUID().toString(), turnId, effectiveSessionId, spaceId, "user", userMessage, now) +
            ChatHistoryEntry(UUID.randomUUID().toString(), turnId, effectiveSessionId, spaceId, "assistant", assistantText, now)
        try {
            file.parentFile?.mkdirs()
            objectMapper.writeValue(file, ChatSessionFile(effectiveSessionId, sessionName, updatedEntries, sessionOwner))
        } catch (e: Exception) {
            log.warn("Could not persist chat history for session {} ({}) to {}", effectiveSessionId, key, file, e)
        }
    }

    companion object {
        private const val UNASSIGNED_KEY = "__unassigned__"
        private const val SESSION_NAME_PREVIEW_LENGTH = 20
    }
}

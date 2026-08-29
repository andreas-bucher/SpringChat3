package ch.arcticsoft.springchat3.chat

import ch.arcticsoft.springchat3.agent.PendingEdit
import ch.arcticsoft.springchat3.agent.RetrievalSummary
import ch.arcticsoft.springchat3.agent.StepTiming
import ch.arcticsoft.springchat3.agent.ToolCallSummary
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
 *
 * [trace] is what the agent actually did to produce this reply - the same
 * step timings, tool calls and document lookup the live panel shows while
 * the turn runs, and that the collapsed "Details" disclosure shows once it
 * lands (2026-08-28, user's own request "The data about the processing of a
 * user message are not stored in the Chat History. When coming back to a
 * chat I do not have the information anymore about the processing."). Null
 * on every user entry (there is no processing to describe until the agent
 * has run) and on every entry recorded before this field existed, which is
 * also exactly how the browser tells "nothing to show" from "showed
 * nothing" - it renders neither, same as it did before this was captured.
 *
 * [sessionOwned] is likewise not on disk and set by the same [getAll]
 * enrichment: whether the owning session has a [ChatSessionFile.owner] at
 * all. Never the owner's address itself - [getAll] only ever returns the
 * caller's own sessions and ownerless ones, so an address here would always
 * be either their own or nobody's, and the one thing the browser actually
 * needs to know is that an ownerless session is visible to everyone and so
 * worth a different warning before deleting it (2026-08-28, see
 * [ChatHistoryStore.delete]).
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
    val sessionOwned: Boolean = false,
    val trace: ChatTrace? = null,
)

/**
 * The processing behind one assistant reply, persisted with it
 * (2026-08-28).
 *
 * **Deliberately the same three types [ch.arcticsoft.springchat3.agent.ChatReply]
 * already hands the browser**, not a parallel set of history-shaped copies:
 * they are already this app's wire vocabulary for a trace (`/chat` returns
 * them, and the `done` event of `/chat/stream` carries them inside its
 * reply), and index.html's `buildTrace` already renders exactly this shape.
 * Copying them into history-local types would mean a converter to keep in
 * step with three classes that change for agent reasons, to gain nothing a
 * reader of the JSON could see. The cost is the one recorded here: these
 * three are now a *persisted* format as well as a wire format, so removing
 * or renaming a field in them makes every session file already on disk
 * unreadable by [ChatHistoryStore.readSession] (which logs and skips it -
 * the session's turns are then orphaned, same as every earlier
 * storage-layout change in this app).
 *
 * Only what the finished reply carried, never the live event stream
 * ([ch.arcticsoft.springchat3.agent.ChatProgressEvent]): re-opening a
 * session should look the way that turn looked once it had landed, and the
 * intermediate "started" events say nothing the finished summaries don't.
 */
data class ChatTrace(
    val toolCalls: List<ToolCallSummary> = emptyList(),
    val steps: List<StepTiming> = emptyList(),
    val retrieval: RetrievalSummary? = null,
    /**
     * Set when this turn's editing step ran, changed nothing and explained
     * itself - so the NEXT turn can understand a bare "yes" (2026-08-29, see
     * [ch.arcticsoft.springchat3.agent.PendingEdit]).
     *
     * A new NULLABLE field on a persisted shape, which is what keeps it
     * compatible: every session file written before today reads it as null,
     * and nothing here treats null as anything but "no question outstanding".
     * Read this file's own note on the three summary types before renaming
     * anything in here.
     */
    val pendingEdit: PendingEdit? = null,
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
 * app. Removing a whole session is [delete] (2026-08-28); there is still
 * no prune/expiry of old sessions, same "no cleanup code until someone
 * actually asks for it" precedent.
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
     *
     * An id that isn't a plain identifier is folded into that same file
     * rather than reaching [sessionFile]: this string is supplied by the
     * browser and becomes a *file name*, so a "../" in one would otherwise
     * write outside the space's own sessions folder - and, since 2026-08-28,
     * let [delete] move a file from outside it into the trash.
     */
    private fun normalizeSessionId(sessionId: String): String =
        safeSessionId(sessionId) ?: "unsessioned".also {
            if (sessionId.isNotBlank()) log.warn("Unusable chat session id {} - recording it under {} instead", sessionId, it)
        }

    /** [sessionId] itself when it is safe to use as a file name, else null - see [normalizeSessionId]. */
    private fun safeSessionId(sessionId: String): String? =
        if (SAFE_SESSION_ID.matches(sessionId)) sessionId else null

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
            .flatMap { session ->
                session.entries.map { it.copy(sessionName = session.sessionName, sessionOwned = session.owner != null) }
            }
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
     * [trace] is recorded on the assistant entry only - it describes how
     * that reply was produced, and the question that prompted it has no
     * processing of its own to describe.
     *
     * `@Synchronized` here is coarser than strictly necessary (blocks a
     * concurrent write to a *different* session's or project's file too,
     * not just the same one) but matches this store's own prior single-lock
     * behavior and this app's real scale (single user) - not worth a
     * per-session lock map for that.
     */
    @Synchronized
    fun recordTurn(
        sessionId: String,
        spaceId: String?,
        userMessage: String,
        assistantText: String,
        owner: String?,
        trace: ChatTrace? = null,
    ) {
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
            ChatHistoryEntry(
                UUID.randomUUID().toString(), turnId, effectiveSessionId, spaceId, "assistant", assistantText, now,
                trace = trace,
            )
        try {
            file.parentFile?.mkdirs()
            objectMapper.writeValue(file, ChatSessionFile(effectiveSessionId, sessionName, updatedEntries, sessionOwner))
        } catch (e: Exception) {
            log.warn("Could not persist chat history for session {} ({}) to {}", effectiveSessionId, key, file, e)
        }
    }

    /**
     * The most recent assistant reply in one session, or null - what
     * "save the summary in a new document" refers to (2026-08-28, from a
     * real failure: [ch.arcticsoft.springchat3.agent.ChatAgent.documentEdit]
     * sees only the current message, so a request to save an earlier reply
     * pointed at text no step of the agent could reach).
     *
     * This store is written on every turn and, until now, read only by the
     * left panel. This is the first read that feeds a turn's own
     * processing - and the reason it stays HERE rather than becoming a
     * general "load the conversation": one reply, named explicitly, is a
     * bounded thing to hand a document-writing step. Feeding whole
     * transcripts back into the agent is a different decision with different
     * costs, and this method should not quietly become it.
     *
     * Ownership is checked the same way [delete] checks it, for the same
     * reason: someone else's session is not yours to read out of, and an
     * ownerless one (recorded before sessions had an owner) belongs to
     * whoever can see the space. Unlike [delete] the space is given rather
     * than searched for - the caller is answering a message in a known
     * space, and [ch.arcticsoft.springchat3.web.ChatController] has already
     * checked read access to it.
     *
     * A blank/empty reply is skipped rather than returned: an empty document
     * is not what anyone means by "save that".
     */
    fun lastAssistantText(sessionId: String, spaceId: String?, email: String): String? {
        val safeId = safeSessionId(sessionId) ?: return null
        val session = readSession(sessionFile(spaceId ?: UNASSIGNED_KEY, safeId)) ?: return null
        if (session.owner != null && !session.owner.equals(email, ignoreCase = true)) return null
        return session.entries.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text
    }

    /**
     * The editing question left open by the last assistant turn of this
     * session, or null when there is none (2026-08-29 - see
     * [ch.arcticsoft.springchat3.agent.PendingEdit] for what it is for).
     *
     * Same session, same ownership check and the same "read it from the file,
     * never from the request body" rule as [lastAssistantText] - a client
     * that could supply this could make the edit step run on any turn it
     * liked, with a question of its own choosing.
     *
     * Deliberately the LAST assistant entry and not a search backwards for
     * the most recent one that happens to carry a question: a question two
     * turns old has already been answered, ignored, or overtaken, and
     * reviving it is how "yes" ends up applied to the wrong request. That
     * is also what makes the whole mechanism one-shot without any expiry
     * bookkeeping.
     */
    fun lastPendingEdit(sessionId: String, spaceId: String?, email: String): PendingEdit? {
        val safeId = safeSessionId(sessionId) ?: return null
        val session = readSession(sessionFile(spaceId ?: UNASSIGNED_KEY, safeId)) ?: return null
        if (session.owner != null && !session.owner.equals(email, ignoreCase = true)) return null
        return session.entries.lastOrNull { it.role == "assistant" }?.trace?.pendingEdit
    }

    /**
     * Removes one whole session - its file, and so every turn in it -
     * returning false when [email] has no such session to remove
     * (2026-08-28, user's own request "Enable to remove chat history").
     *
     * **The search is the permission check.** Only [visibleSpaceIds] are
     * looked in, plus the unassigned fallback that belongs to no space and
     * is readable by everyone under the same legacy rule [getAll] applies -
     * so a session in a space the caller cannot see isn't found at all, and
     * [ch.arcticsoft.springchat3.web.ChatHistoryController] needs no
     * separate access call for a space this store is the one to identify.
     * Read access, deliberately not write: a chat belongs to whoever had
     * it, so a VIEWER may delete their own chats in a space they cannot
     * otherwise change - the one place in this app where a viewer destroys
     * something (2026-08-24, see springchat3_multi_user.md in project
     * memory for what a viewer is).
     *
     * A session that exists but belongs to someone else is false too,
     * indistinguishable from one that never existed, so `DELETE` cannot be
     * used to find out which session ids are real.
     *
     * An **ownerless** session - recorded before [ChatSessionFile.owner]
     * existed, and therefore shown to everyone by [getAll] - is deletable
     * by anyone who can see it. The alternative is a row that is visible
     * forever and removable by nobody, which is the thing this feature
     * exists to fix; index.html says plainly that it goes for everyone
     * before it asks.
     *
     * `@Synchronized`, so a delete cannot interleave with [recordTurn]
     * writing the same file - the loser of that race would otherwise
     * re-create the session it just removed.
     */
    @Synchronized
    fun delete(sessionId: String, email: String, visibleSpaceIds: Set<String>): Boolean {
        val safeId = safeSessionId(sessionId) ?: return false
        for (key in visibleSpaceIds + UNASSIGNED_KEY) {
            val session = readSession(sessionFile(key, safeId)) ?: continue
            if (session.owner != null && !session.owner.equals(email, ignoreCase = true)) continue
            return moveToTrash(sessionFile(key, safeId), key, safeId)
        }
        return false
    }

    /**
     * Moved, not deleted - one rename into `[dataDir]/trash/sessions/`,
     * which the app never scans, so the session is gone from its point of
     * view while a mistake stays recoverable by hand. Same convention
     * [ch.arcticsoft.springchat3.project.ProjectStore.moveToTrash] already
     * uses for a whole space, timestamp suffix included so deleting two
     * sessions with the same id (one per space) cannot collide.
     */
    private fun moveToTrash(file: File, key: String, sessionId: String): Boolean {
        val trash = File(File(dataDir, "trash"), "sessions")
        trash.mkdirs()
        val target = File(trash, key + "-" + sessionId + "-" + System.currentTimeMillis() + ".json")
        if (!file.renameTo(target)) {
            log.warn("Could not move chat session {} ({}) to {} - leaving it in place", sessionId, key, target)
            return false
        }
        log.info("Deleted chat session {} ({}) - its file is now {}", sessionId, key, target)
        return true
    }

    companion object {
        private const val UNASSIGNED_KEY = "__unassigned__"
        private const val SESSION_NAME_PREVIEW_LENGTH = 20

        /**
         * What a session id may contain to be used as a file name - the
         * browser's own `crypto.randomUUID()` and the "unsessioned"
         * fallback both fit; nothing with a path separator, a dot or a
         * `~` in it does.
         */
        private val SAFE_SESSION_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

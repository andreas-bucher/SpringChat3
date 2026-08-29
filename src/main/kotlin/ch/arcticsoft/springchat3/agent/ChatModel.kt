package ch.arcticsoft.springchat3.agent

/**
 * Incoming chat turn from the WebFlux layer.
 *
 * [latitude]/[longitude] come from the browser's Geolocation API (see
 * static/index.html) - null if the user's browser doesn't support it,
 * hasn't granted permission, or the frontend didn't bother asking (e.g. a
 * plain curl request). Read by [ChatAgent.analyzeMessage] to construct a
 * per-request [ch.arcticsoft.springchat3.tools.CurrentLocationTool] - see
 * that class's doc comment for why the coordinates are baked into the tool
 * object itself rather than something the LLM supplies as an argument.
 *
 * [correlationId] is set by ChatController's `/chat/stream` endpoint (a
 * fresh random ID per request) so [ChatAgent] can attribute the live
 * [ChatProgressEvent]s it emits while working (see ChatProgress.kt) to the
 * right in-flight browser connection via [ChatProgressBus]. It's blank for
 * the plain `/chat` endpoint (and for any caller that doesn't set it, e.g. a
 * raw curl request) - [ChatProgressBus.emit] is a harmless no-op for a
 * correlation ID nothing ever [ChatProgressBus.open]ed.
 */
data class ChatRequest(
    val message: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val correlationId: String = "",
    /**
     * Ids of documents previously uploaded via
     * [ch.arcticsoft.springchat3.web.DocumentController.upload] and stored
     * in [ch.arcticsoft.springchat3.document.DocumentStore] - empty if no
     * document is attached to this conversation. Was a single nullable
     * `documentId: String?` until 2026-08-22 (user's own request "Can we
     * support that a user selects multiple documents and then the question
     * is answered based on all these?") - the side panel's document
     * selection is no longer exclusive (see index.html's
     * `activeDocumentIds`), so this carries every currently selected
     * document's id, in no particular order. Looked up (not dereferenced
     * here) by [ChatAgent.answer], which folds each one's (size-capped)
     * text directly into the generation prompt - see
     * springchat3_document_qa.md in project memory for why this
     * deliberately does NOT go through [ChatAgent.analyzeMessage]'s tool
     * loop the way [latitude]/[longitude] do via `CurrentLocationTool`: a
     * document's extracted text can be far larger than anything else this
     * app round-trips through the small tool-selection model's own context.
     */
    val documentIds: List<String> = emptyList(),
    /**
     * Whichever project was active in the browser (index.html's
     * `activeProjectId`) when this message was sent, or null if none was -
     * only possible before a first project exists, since
     * [ch.arcticsoft.springchat3.web.ProjectController]'s own frontend
     * caller auto-selects one otherwise (see springchat3_projects_panel.md
     * v10 in project memory). Not read by [ChatAgent] at all - the only
     * consumer is [ChatController], which passes it straight through to
     * [ch.arcticsoft.springchat3.chat.ChatHistoryStore.recordTurn] so the
     * captured turn is tagged with the right project (2026-08-23, user's own
     * request "the chat history shall be by project" - see
     * springchat3_projects_panel.md in project memory), the same "fixed at
     * the moment of the action, not re-read later" convention every other
     * `spaceId` field in this app already follows.
     */
    val spaceId: String? = null,
    /**
     * Whichever chat session was active in the browser (index.html's
     * `activeSessionId`) when this message was sent - a `crypto.randomUUID()`
     * minted once when the page first loads, or freshly re-minted whenever
     * the user clicks "New Chat" (2026-08-23, user's own request "add a chat
     * sessionId to the chat-history, append questions and answer to the same
     * chat session. only when new chat sessions is create, start with new
     * chat-history, save in new file." - see springchat3_projects_panel.md
     * in project memory). Not read by [ChatAgent] at all - the only consumer
     * is [ChatController], which passes it straight through to
     * [ch.arcticsoft.springchat3.chat.ChatHistoryStore.recordTurn] so every
     * turn from the same browser session lands in the same session file,
     * the same "fixed at the moment of the action, not re-read later"
     * convention [spaceId] above already follows. Defaults to blank for
     * any caller that doesn't set it (e.g. a raw curl request, or a browser
     * tab left open from before this field existed) -
     * [ch.arcticsoft.springchat3.chat.ChatHistoryStore] folds a blank id
     * into one shared fallback session per project rather than rejecting it
     * - see that class's own doc comment.
     */
    val sessionId: String = "",
    /**
     * Whether this turn is allowed to *change* anything in [spaceId] - set
     * by [ch.arcticsoft.springchat3.web.ChatController.authorize] from the
     * caller's role in that space (2026-08-24, shared spaces - see
     * springchat3_multi_user.md in project memory), and read only by
     * [ChatAgent.documentEdit].
     *
     * **Server-set, never client-supplied.** It is part of this request
     * body's shape, so a client can send it; [ChatController] overwrites it
     * on every path before the agent ever sees it. Defaults to false so that
     * any future caller which forgets loses the edit tools rather than
     * silently gaining them - the same fail-closed choice
     * [ch.arcticsoft.springchat3.project.SpaceAccess] makes for a missing
     * identity.
     */
    val documentEditingAllowed: Boolean = false,
    /**
     * Whether the agent may use tools for this turn - the *caller's* own
     * setting, resolved by
     * [ch.arcticsoft.springchat3.settings.SettingsResolver] and stamped by
     * [ch.arcticsoft.springchat3.web.ChatController.authorize] (2026-08-25,
     * the per-user settings split - see springchat3_settings.md in project
     * memory). [ChatAgent.analyzeMessage] reads it instead of reaching into
     * the settings store, which is what makes "per user" possible at all: the
     * agent is a singleton and has no idea who is asking.
     *
     * **Server-set, never client-supplied**, exactly like
     * [documentEditingAllowed] above - it is part of this body's shape, so a
     * client can send it, and [ChatController] overwrites it on every path.
     * Defaults to false so a caller that somehow skips that stamping loses
     * the tools rather than silently gaining them.
     */
    val toolsEnabled: Boolean = false,
    /**
     * The caller's effective role-to-model overrides for this turn (keys are
     * [ch.arcticsoft.springchat3.settings.ModelRoleKeys] constants), already
     * merged over the server defaults and filtered through the admin's model
     * allow-list. **Sparse on purpose**: a role missing here means "use the
     * configured default", which [ChatAgent] still does through
     * `withLlmByRole`/`withDefaultLlm` rather than by being handed an exact
     * tag - see [ch.arcticsoft.springchat3.settings.SettingsResolver].
     *
     * Server-set and overwritten unconditionally, same as the two fields
     * above: it decides which model runs, so accepting it from the body would
     * hand any caller a way around the allow-list.
     */
    val modelOverrides: Map<String, String> = emptyMap(),
    /**
     * The documents this caller has unlocked for editing (2026-08-25) - see
     * [ch.arcticsoft.springchat3.settings.UserSettings.editableDocumentIds]
     * for why the unlock is per user rather than a property of the document.
     *
     * Read by [ChatAgent.documentEdit], which hands it to
     * [ch.arcticsoft.springchat3.tools.WordDocumentEditTool] as a **second
     * hard scope** beside [documentIds]: a document must be both attached to
     * this turn and unlocked by this person before a single byte of it can
     * change.
     *
     * **Server-set, never client-supplied**, like the three fields above.
     * Empty by default, so a caller that somehow skips the stamping edits
     * nothing rather than everything.
     */
    val editableDocumentIds: Set<String> = emptySet(),
    /**
     * The last assistant reply in this browser session, when there is one
     * (2026-08-28) - so "save the summary in a new document" has something
     * to save. Stamped by [ch.arcticsoft.springchat3.web.ChatController]
     * from [ch.arcticsoft.springchat3.chat.ChatHistoryStore], and, like the
     * four fields above it, overwritten unconditionally rather than trusted
     * from the request body: it is read out of another user's session file
     * if the client is allowed to choose it.
     *
     * Read only by [ChatAgent.documentEdit], which hands it to
     * [ch.arcticsoft.springchat3.tools.WordDocumentEditTool] so the text can
     * be saved VERBATIM. It is deliberately not put in any prompt: the point
     * is to save exactly what the user read, and a model asked to pass a
     * long reply through a tool argument will paraphrase, truncate or
     * mangle it.
     *
     * This turn's own answer is NOT here and cannot be - [ChatAgent.answer]
     * runs after [ChatAgent.documentEdit] by data dependency. "Summarise
     * this and save it" is therefore a two-turn request by construction.
     */
    val previousAnswer: String? = null,
    /**
     * What the editing step left unresolved on the PREVIOUS turn, or null -
     * the state that makes a bare "yes" mean something (2026-08-29, user's
     * own report: "when the answer was a question regarding editing the
     * document, and the user answers with yes, the Editing document does not
     * kick in").
     *
     * Two distinct failures needed this, and only fixing both helps:
     *  1. "yes" carries no change verb, so [ChatAgent.looksLikeDocumentChange]
     *     short-circuits [ChatAgent.documentEdit] before any LLM call.
     *  2. Even bypassing that, the step is built from [message] plus the
     *     scope and the guardrails and NOTHING else - a step handed the
     *     single word "yes" cannot know what was asked. Bypassing the filter
     *     alone would only buy a more expensive failure.
     *
     * Stamped by [ch.arcticsoft.springchat3.web.ChatController] from the last
     * assistant entry's own trace, exactly as [previousAnswer] is, and for
     * the same reason: it is read out of a session file, so the client is
     * never trusted to supply it.
     *
     * **One-shot by construction, with no expiry logic.** It always comes
     * from the IMMEDIATELY previous assistant entry, so as soon as the next
     * turn writes a trace of its own - whether it edited something or
     * declined again - the old question is gone. A stale question cannot keep
     * re-arming the edit step turn after turn.
     */
    val pendingEdit: PendingEdit? = null,
)

/**
 * An unfinished editing exchange: what the editing step said when it changed
 * nothing, and the message it was answering.
 *
 * Both halves are needed. [question] is what the user was asked; [askedAbout]
 * is the request that prompted it, and is what makes the follow-up
 * actionable - "yes" is only meaningful next to "make the stories longer,
 * around 500 characters".
 *
 * Persisted inside [ch.arcticsoft.springchat3.chat.ChatTrace], so it survives
 * a restart and shows up in the stored turn like every other trace field.
 * A new nullable field on an existing persisted shape: older session files
 * simply read it as null.
 */
data class PendingEdit(
    val question: String,
    val askedAbout: String,
)

/**
 * [ChatAgent.analyzeMessage]'s own createObject target - the small planning
 * model's one-line summary of what it did, if anything. Deliberately
 * lightweight and mostly discarded: the actual tool-call results
 * [ChatAgent.answer] works from come from [ToolCallProgressBridge]'s capture
 * of the real tool calls (see that class's doc comment), not from this
 * model-written note - the note only exists because `createObject` needs
 * *some* target type, and a short "what did you just do" summary is a cheap
 * way to keep the small model's own output legible in logs if you go
 * looking, without asking it to re-digest or summarize raw tool output the
 * way a dedicated summarization pass would (this app deliberately has none -
 * see [ChatAgent]'s class doc comment).
 */
data class ToolGatheringNote(val note: String = "")

/**
 * Raw output of one tool call the LLM made natively via
 * `PromptRunner.withToolObject(...)` (Spring AI/Embabel's real function
 * calling - see [ch.arcticsoft.springchat3.tools.GeoTool]'s doc comment),
 * plus how long it took to run.
 *
 * [tool] is the tool's registered name (its `@Tool(name = ...)`, e.g.
 * `"lookup_place"`) rather than an enum - there's no longer a fixed,
 * hand-maintained list of tools application code has to switch over, since
 * dispatch now happens inside Spring AI's own tool-calling machinery, not a
 * `when (call.tool)` this app writes itself.
 *
 * [input] is the tool call's raw argument payload as Embabel's
 * `ToolCallRequestEvent`/`ToolCallResponseEvent` report it (attested, not
 * compile-verified, to be the JSON-encoded arguments object, e.g.
 * `{"place":"Interlaken"}` - see [ToolCallProgressBridge]'s doc comment) -
 * not a single free-text "query" string the way this app's previous
 * hand-rolled dispatch worked.
 *
 * Captured by [ToolCallProgressBridge] (an `AgenticEventListener`) rather
 * than built inline in a loop [ChatAgent] itself controls, since the actual
 * tool dispatch now happens inside `createObject(...)`, not in application
 * code between two steps of a manual plan/execute split.
 */
data class ToolExecution(
    val tool: String,
    val input: String,
    val rawOutput: String,
    val durationMs: Long,
)

/** All tool executions [ChatAgent.analyzeMessage] made this turn (empty if none were needed). */
data class ToolResults(val executions: List<ToolExecution>, val timings: List<StepTiming> = emptyList())

/**
 * One tool call surfaced to the UI alongside the reply, so the chat frontend
 * can show what the agent actually did this turn (e.g. a small "Lookup
 * place: Interlaken" chip) instead of the tool use being invisible.
 *
 * [failed] is a best-effort heuristic, not a hard guarantee - see
 * [ToolCallProgressBridge] for how it's derived from the tool call's
 * `Result<String>`.
 *
 * [seconds] is wall-clock time for this one call, so the UI can show e.g.
 * "Lookup place 0.4s".
 *
 * [step] names the pipeline step this call belongs to - the same label the
 * matching [StepTiming] carries - so the UI can nest it under the right row
 * (2026-08-28). Null means [ChatAgent.analyzeMessage]'s gathering step,
 * which is where the frontend has always put an unattributed call and where
 * every call recorded before this field existed came from.
 *
 * [outcome] is a short, human-readable version of the tool's own result,
 * set only for [ChatAgent.documentEdit]'s calls (2026-08-28): an edit is the
 * one kind of tool call whose result the user has a direct stake in, since
 * it changed their document. Null everywhere else, and the UI shows nothing
 * extra for a null.
 *
 * Both are nullable with defaults on purpose: this class is a PERSISTED
 * format (see springchat3_chat_history_trace.md in project memory), so a
 * session file written before either existed still reads back.
 */
data class ToolCallSummary(
    val tool: String,
    val input: String,
    val failed: Boolean,
    val seconds: Double,
    val step: String? = null,
    val outcome: String? = null,
)

/**
 * Wall-clock time spent in one pipeline step (an @Action method), for the
 * UI's step-by-step timeline - the agent-level equivalent of
 * [ToolCallSummary.seconds] for individual tool calls. [step] is a short,
 * human-readable label (e.g. "Analyzing message ..."), not the Kotlin
 * method/action name.
 */
data class StepTiming(val step: String, val seconds: Double)

/**
 * Surfaced to the UI (2026-08-22) alongside a reply, the same idea as
 * [ToolCallSummary] but for the document lookup
 * [ch.arcticsoft.springchat3.agent.ChatAgent.answer] does inside its own
 * two-stage search (2026-08-22, see springchat3_document_qa.md in project
 * memory) - a small "Document search: report.pdf - 6 passages - 0.2s" (or,
 * for a structural question, "Document structure: report.pdf - 4 sections -
 * 0.0s") chip, shown the same way a tool call chip is, even though this
 * isn't an LLM-invoked tool call the way [ToolCallSummary] entries are (see
 * [ChatRequest.documentIds]'s doc comment for why retrieval deliberately
 * never goes through [ChatAgent.analyzeMessage]'s tool loop) - hence a
 * separate type rather than reusing [ToolCallSummary] itself. Only present
 * when at least one document was actually attached to this turn
 * ([ChatReply.retrieval] is null otherwise, same convention as
 * [ChatRequest.documentIds]).
 *
 * [via] is `"structure"` when [ChatAgent.answer] answered from the
 * document's extracted outline (see
 * [ch.arcticsoft.springchat3.document.DocumentStructureStore]) instead of a
 * vector-store search, `"vector"` for the original chunk-search path, or
 * `"structure+vector"` for the (not yet actually produced, but supported -
 * see [DocumentSearchStrategy]'s doc comment) case where both ran. The UI
 * uses it to pick the right label/noun.
 *
 * [resultCount] can be 0 (a document is attached but nothing relevant
 * turned up for this question) - still worth showing, since it tells the
 * user a search actually happened rather than nothing occurring at all. Was
 * `chunksFound` until the structure-search path was added (2026-08-22) -
 * renamed since it now also counts top-level outline entries, not just
 * vector-store chunks.
 *
 * [filenames] was a single [String] until 2026-08-22, when the side panel's
 * document selection stopped being exclusive (see
 * [ChatRequest.documentIds]'s doc comment) - one entry per attached
 * document this retrieval actually covered, in the same order
 * [ChatAgent.answer] processed them. [resultCount]/[seconds]/[via] stay
 * single aggregate values across every attached document rather than one
 * set per document, same simplification [ChatAgent.documentSearchStrategy]
 * makes for its own classification - see that method's doc comment.
 */
data class RetrievalSummary(
    val filenames: List<String>,
    val resultCount: Int,
    val seconds: Double,
    val via: String,
)

/**
 * [ChatAgent.documentSearchStrategy]'s own createObject target (2026-08-22,
 * see springchat3_document_qa.md in project memory) - the new
 * "document-search-strategy" small model's raw classification of one
 * question, before application code turns it into the richer
 * [DocumentSearchStrategy] that [ChatAgent.answer] actually consumes. Kept
 * separate from that richer type for the same reason [ToolGatheringNote]
 * stays separate from [ToolResults] - this is exactly what the LLM was
 * asked to produce, nothing derived or computed added yet.
 *
 * Two independent fields, matching the two independent questions
 * [ChatAgent.documentSearchStrategy]'s own prompt asks the model to answer
 * (see that method) - an earlier version of this class had a single
 * `preferOutline` boolean, from before the "both at once" case existed;
 * [useVector] defaults `true` (the safer choice - a missed content search
 * loses real information, an unnecessary one just costs a bit of latency)
 * and [useStructure] defaults `false`, so a JSON response missing either
 * field entirely degrades to plain vector search rather than failing to
 * parse.
 */
data class DocumentQuestionClassification(val useStructure: Boolean = false, val useVector: Boolean = true)

/**
 * [ChatAgent.documentSearchStrategy]'s output (2026-08-22, see
 * springchat3_document_qa.md in project memory), consumed by
 * [ChatAgent.answer] to decide how to build its document context - replaces
 * an earlier plain keyword heuristic (`looksStructural`) that over-triggered
 * on ordinary phrasing having nothing to do with the document's actual
 * structure, with a small dedicated LLM classification instead.
 *
 * Deliberately two independent flags rather than one structure-or-vector
 * choice, even though today's classification only ever sets one of them -
 * the shape is ready for a later version that pulls *some* context from the
 * outline and *some* from a vector search in the same turn (discussed
 * 2026-08-22, not yet built) without another type change; [ChatAgent.answer]
 * already merges whichever of [useStructure]/[useVector] end up true rather
 * than treating them as mutually exclusive - see its own retrieval-block
 * comment. [useVector] is forced on by `answer()` whenever [useStructure]
 * ends up unusable (no structure actually available) or wasn't chosen, so a
 * document question is never left with neither search running.
 *
 * [seconds] is how long [ChatAgent.documentSearchStrategy] itself took -
 * near 0.0 whenever no LLM call actually happened (the attached document has
 * no extracted structure to classify a question against; see that method's
 * short-circuits), otherwise the classification call's real latency.
 * Surfaced to the UI as its own trace step (2026-08-22, user's own request)
 * - `"Document search strategy ..."`, sibling to `"Analyzing message ..."`
 * and the retrieval row, not folded into either of their reported times.
 *
 * **Multi-document (2026-08-22, see [ChatRequest.documentIds]'s doc
 * comment):** one [DocumentSearchStrategy] is still decided per turn, not
 * one per attached document - when more than one document is selected,
 * [ChatAgent.documentSearchStrategy] classifies once against every attached
 * document's outline shown together (labeled by filename), and the single
 * resulting [useStructure]/[useVector] pair is applied uniformly to each
 * attached document in [ChatAgent.answer] (a document with no outline of
 * its own simply falls back to vector search regardless, same as before).
 * Keeps this a single small-model call per turn rather than one per
 * document, at the cost of not letting different documents take different
 * search paths in the same turn - acceptable given how rarely a real
 * question would actually need that.
 */
data class DocumentSearchStrategy(
    val useStructure: Boolean,
    val useVector: Boolean,
    val seconds: Double = 0.0,
)

/**
 * What [ChatAgent.documentEdit] did to the user's documents this turn, if
 * anything (2026-08-23, added when document editing moved out of [answer]
 * into its own action - see that action's own doc comment for why).
 *
 * [executions] is the honest record: the actual tool calls that ran, with
 * their inputs and raw outputs, exactly as [ToolResults.executions] records
 * the gathering step's. [answer] renders these into its prompt so the reply
 * describes what really happened rather than what a model intended, and
 * merges them into the reply's own `toolCalls` so an edit shows in the UI
 * trace beside the lookups that led to it.
 *
 * An empty [executions] is the overwhelmingly common case - most turns ask
 * for nothing to be changed.
 *
 * [note] is that step's own closing words, and it is NOT the honest record -
 * [executions] is. Carried across since 2026-08-29; before that it was
 * logged and dropped, which meant the one step that knew what it had decided
 * could not say anything to the user, and anything it wanted to ASK reached
 * nobody. That is why the ambiguity guard had to move into
 * [ch.arcticsoft.springchat3.tools.WordDocumentEditTool]'s own code, where a
 * refusal comes back as a tool result: a result crosses, prose did not.
 *
 * Treat [note] strictly as commentary. A model that emits its tool-call
 * syntax as ordinary text writes a note claiming a change it never made -
 * that is a real, observed failure here, not a hypothetical - so [answer]'s
 * prompt says plainly that a note contradicting [executions] is wrong. The
 * one case where it is worth reading is the one [executions] cannot express:
 * the step ran, chose to change nothing, and said why - which is also where
 * a question back to the user lives.
 *
 * That does mean "nothing was changed" is no longer one flat state: an empty
 * [executions] with a [note] is "it ran and declined", and with no note is
 * "it never really ran". Deliberate, and the reason this field exists.
 *
 * [seconds] is 0.0 whenever the step short-circuited, which is also how
 * [answer] decides whether to show it as a step in the UI timeline at all -
 * same visibility rule [DocumentSearchStrategy.seconds] already follows.
 */
data class DocumentEdits(
    val executions: List<ToolExecution> = emptyList(),
    val seconds: Double = 0.0,
    val note: String? = null,
)

/**
 * Final reply returned to the caller. [toolCalls], [steps], and [retrieval]
 * are all populated in code, never by an LLM.
 */
data class ChatReply(
    val text: String,
    val toolCalls: List<ToolCallSummary> = emptyList(),
    val steps: List<StepTiming> = emptyList(),
    val retrieval: RetrievalSummary? = null,
    /**
     * Set only when this turn's editing step ran, changed nothing and said
     * why - the state [ChatAgent.answer]'s middle guidance branch already
     * detects. [ch.arcticsoft.springchat3.web.ChatController] copies it into
     * the stored trace, and the NEXT turn reads it back as
     * [ChatRequest.pendingEdit]. Nothing in the browser reads it.
     */
    val pendingEdit: PendingEdit? = null,
)

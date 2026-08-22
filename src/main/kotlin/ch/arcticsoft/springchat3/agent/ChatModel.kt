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
 */
data class ToolCallSummary(
    val tool: String,
    val input: String,
    val failed: Boolean,
    val seconds: Double,
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

/** Just the reply text - what the single answering LLM call is actually asked to produce. */
data class AnswerText(val text: String)

/**
 * Final reply returned to the caller. [toolCalls], [steps], and [retrieval]
 * are all populated in code, never by an LLM.
 */
data class ChatReply(
    val text: String,
    val toolCalls: List<ToolCallSummary> = emptyList(),
    val steps: List<StepTiming> = emptyList(),
    val retrieval: RetrievalSummary? = null,
)

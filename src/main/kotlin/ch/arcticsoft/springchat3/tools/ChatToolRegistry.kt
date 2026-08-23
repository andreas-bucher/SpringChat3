package ch.arcticsoft.springchat3.tools

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Auto-collects every singleton [ChatTool] `@Component` bean the same way
 * SpringChat2's `ToolRegistry` auto-collects `List<Tool>` beans
 * (`~/repos/SpringChat2`, `tool/ToolRegistry.java`) - Spring populates the
 * constructor list from every bean implementing the marker interface, so
 * adding a new tool is just adding a new `@Component class SomeTool :
 * GatheringTool` with `@Tool`-annotated methods; nothing here or in
 * [ch.arcticsoft.springchat3.agent.ChatAgent] has to change.
 *
 * **Two categories, one registry** (2026-08-23, after the user asked whether
 * there should instead be two separate registries, one per agent step): the
 * tools now split into read-only [GatheringTool]s and side-effecting
 * [EditingTool]s, which different steps are allowed to use - see those
 * interfaces' own doc comments. Kept as one bean with two typed accessors
 * rather than two beans, because (a) one startup log line shows both
 * categories side by side, which is exactly what you want to eyeball now
 * that they differ in what they may do, (b) a tool qualifying as both has
 * one obvious home rather than an ambiguous one, and (c) a bare [ChatTool]
 * that fell into neither category would be silently collected by nobody -
 * here it's a startup warning instead.
 *
 * Neither accessor covers the per-request tools ([CurrentLocationTool],
 * [WordDocumentReadTool], [WordDocumentEditTool]): those aren't Spring beans
 * at all - each needs something from the current chat turn (browser
 * coordinates, the active project) - so [ch.arcticsoft.springchat3.agent.ChatAgent]
 * constructs them per request and appends them to whichever list they belong
 * in. Their marker interfaces still do the real work there: the two lists
 * are typed `List<GatheringTool>` and `List<EditingTool>`, so a tool can't be
 * appended to the wrong one.
 *
 * No `descriptors()`/`execute(name, args)` here unlike SpringChat2's
 * `ToolRegistry` - see [ChatTool]'s doc comment for why: Spring AI already
 * builds each tool's schema and dispatches calls by reflecting over
 * `@Tool`-annotated methods, so this registry's only job is handing
 * `PromptRunner.withToolObjects(...)` the object list.
 *
 * Logs once, in the `init` block below - by the time Spring invokes this
 * constructor, `tools` is already fully populated (bean dependencies are
 * resolved before construction), so this fires exactly once at startup,
 * right after every [ChatTool] bean has been collected. Logs by simple class
 * name ([ChatTool] carries no `name()`/`descriptor()` of its own to log
 * instead), which is enough to sanity-check at startup that every expected
 * tool bean got picked up, and now also which side of the read/write line it
 * landed on.
 */
@Component
class ChatToolRegistry(private val tools: List<ChatTool>) {
    private val log = LoggerFactory.getLogger(ChatToolRegistry::class.java)

    private val gathering = tools.filterIsInstance<GatheringTool>()
    private val editing = tools.filterIsInstance<EditingTool>()

    init {
        log.info(
            "ChatToolRegistry initialized with {} tool bean(s) - gathering: [{}], editing: [{}]",
            tools.size,
            gathering.joinToString { it.simpleName() },
            editing.joinToString { it.simpleName() },
        )
        val uncategorized = tools.filter { it !is GatheringTool && it !is EditingTool }
        if (uncategorized.isNotEmpty()) {
            log.warn(
                "{} tool bean(s) implement ChatTool but neither GatheringTool nor EditingTool, so no agent step " +
                    "will ever offer them to a model: [{}]",
                uncategorized.size,
                uncategorized.joinToString { it.simpleName() },
            )
        }
    }

    private fun ChatTool.simpleName(): String = this::class.simpleName ?: toString()

    /** Read-only tools, for [ch.arcticsoft.springchat3.agent.ChatAgent.analyzeMessage]. */
    fun gatheringTools(): List<GatheringTool> = gathering

    /**
     * Side-effecting tools, for [ch.arcticsoft.springchat3.agent.ChatAgent.documentEdit].
     * Empty today - the only editing tool ([WordDocumentEditTool]) is
     * per-request, not a bean - and that's fine: the accessor exists so the
     * editing step has one place to ask, and so a future singleton editing
     * tool is picked up with no wiring change, exactly like a gathering one.
     */
    fun editingTools(): List<EditingTool> = editing
}

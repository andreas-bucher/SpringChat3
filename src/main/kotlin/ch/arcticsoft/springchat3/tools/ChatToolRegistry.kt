package ch.arcticsoft.springchat3.tools

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Auto-collects every singleton [ChatTool] `@Component` bean ([GeoTool])
 * the same way SpringChat2's `ToolRegistry` auto-collects
 * `List<Tool>` beans (`~/repos/SpringChat2`, `tool/ToolRegistry.java`) -
 * Spring populates the constructor list from every bean implementing the
 * marker interface, so adding a new tool is just adding a new `@Component
 * class SomeTool : ChatTool` with `@Tool`-annotated methods; nothing here or
 * in [ch.arcticsoft.springchat3.agent.ChatAgent] has to change.
 *
 * Deliberately doesn't cover [CurrentLocationTool]: that one isn't a Spring
 * bean at all (it needs the current request's browser coordinates, which
 * differ per chat turn - see its own doc comment), so `ChatAgent.analyzeMessage`
 * still constructs it itself, per request, and appends it to [tools]'s
 * result before calling `withToolObjects(...)`.
 *
 * No `descriptors()`/`execute(name, args)` here unlike SpringChat2's
 * `ToolRegistry` - see [ChatTool]'s doc comment for why: Spring AI already
 * builds each tool's schema and dispatches calls by reflecting over
 * `@Tool`-annotated methods, so this registry's only job is handing
 * `PromptRunner.withToolObjects(...)` the object list.
 *
 * Logs the registered tools once, in the `init` block below - by the time
 * Spring invokes this constructor, `tools` is already fully populated (bean
 * dependencies are resolved before construction), so this fires exactly once
 * at startup, right after every [ChatTool] bean has been collected. Logs by
 * simple class name ([ChatTool] carries no `name()`/`descriptor()` of its
 * own to log instead - see this class's doc comment above for why), which is
 * enough to sanity-check at startup that every expected tool bean actually
 * got picked up (e.g. after adding a new one, or if a `@Component` was
 * accidentally left off).
 */
@Component
class ChatToolRegistry(private val tools: List<ChatTool>) {
    private val log = LoggerFactory.getLogger(ChatToolRegistry::class.java)

    init {
        log.info(
            "ChatToolRegistry initialized with {} tool(s): {}",
            tools.size,
            tools.joinToString { it::class.simpleName ?: it.toString() },
        )
    }

    fun tools(): List<ChatTool> = tools
}

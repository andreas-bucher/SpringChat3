package ch.arcticsoft.springchat3.tools

/**
 * Marker interface for chat tools, so [ChatToolRegistry] can ask Spring to
 * auto-collect every tool bean instead of [ch.arcticsoft.springchat3.agent.ChatAgent]
 * hardcoding a `listOf(geoTool, transportTool, ...)` that has to be edited by
 * hand every time a tool is added or removed - mirrors SpringChat2's own
 * `Tool`/`ToolRegistry` pattern (`~/repos/SpringChat2`, `tool` package),
 * adapted to this app's architecture.
 *
 * Deliberately carries no methods of its own, unlike SpringChat2's `Tool`
 * interface (`name()`/`descriptor()`/`execute(args)`). SpringChat2's hand-rolled
 * `OllamaClient` talks to Ollama's `tools` API directly, so application code
 * has to build each tool's JSON schema itself and dispatch each call by name.
 * Here, Spring AI's `@Tool`/`@ToolParam` annotations (see [GeoTool]'s doc
 * comment) already let `PromptRunner.withToolObjects(...)` build the schema
 * via reflection and dispatch the call itself - a tool becomes callable by
 * having annotated methods, not by satisfying an interface contract. This
 * marker exists purely so [ChatToolRegistry] has a type to collect by -
 * Spring can't usefully autowire `List<Any>`.
 *
 * **Never implement this directly** - implement [GatheringTool] or
 * [EditingTool] below. A bare [ChatTool] belongs to no step and is offered
 * to no model; [ChatToolRegistry] logs a warning at startup if it finds one,
 * rather than letting it sit there looking wired up.
 */
interface ChatTool

/**
 * A read-only tool: it looks something up and returns it, and running it
 * twice changes nothing. These are the tools
 * [ch.arcticsoft.springchat3.agent.ChatAgent.analyzeMessage] offers its
 * (small, fast) tool-selection model - a step whose whole job is deciding
 * which lookups this turn needs.
 */
interface GatheringTool : ChatTool

/**
 * A tool with side effects - today, changing a Word document
 * ([WordDocumentEditTool]). Offered **only** to
 * [ch.arcticsoft.springchat3.agent.ChatAgent.documentEdit], the one step
 * allowed to change anything (2026-08-23, user's own decision that editing
 * belongs after the analysis step, then its own follow-up that it deserves
 * its own action rather than riding along with reply generation).
 *
 * This split is a safety property, not tidiness. Before it existed, the only
 * thing keeping an editing tool away from the 3B tool-selection model was
 * remembering not to annotate it `@Component` - a silent, un-compilable
 * invariant. Now the two steps ask [ChatToolRegistry] for different types,
 * and handing an editing tool to the gathering step doesn't compile.
 */
interface EditingTool : ChatTool

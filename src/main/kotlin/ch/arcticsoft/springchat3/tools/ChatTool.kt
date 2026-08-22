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
 * marker exists purely so [ChatToolRegistry] has a type to collect `List<ChatTool>`
 * by - Spring can't usefully autowire `List<Any>`.
 */
interface ChatTool

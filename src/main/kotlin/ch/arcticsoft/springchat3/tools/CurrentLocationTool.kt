package ch.arcticsoft.springchat3.tools

import org.springframework.ai.tool.annotation.Tool

/**
 * The LLM-facing "where am I" tool - deliberately separate from [GeoTool],
 * and deliberately takes no LLM-supplied parameters at all.
 *
 * Why a separate tool rather than a sentinel value passed to
 * [GeoTool.lookupPlace] (this app's previous approach): the model has no way
 * to know the user's actual coordinates, so asking it to supply *any*
 * argument for "my own location" just invites it to guess or echo something
 * meaningless (this app previously had to work around exactly that - a small
 * planning model kept echoing the tool's own name, "LOCATION", as if it were
 * the place to look up). A tool with an empty parameter schema removes the
 * failure mode structurally instead of patching around it: there's nothing
 * for the model to get wrong, because there's nothing for it to supply.
 * (SpringChat2's `UserLocationTool` made the same call, for the same reason -
 * see its class doc comment.)
 *
 * Not a `@Component` - unlike [GeoTool] (a stateless singleton
 * bean), this needs the *current request's* browser-reported coordinates,
 * which differ per chat turn. [ch.arcticsoft.springchat3.agent.ChatAgent.analyzeMessage]
 * constructs a fresh instance per request, closing over
 * [ch.arcticsoft.springchat3.agent.ChatRequest.latitude]/`.longitude`, and
 * passes that instance to `context.ai().withToolObject(...)` - so by the
 * time the model can call [getUserCurrentLocation], the real coordinates are
 * already baked into the object it's calling, not something it supplies.
 *
 * Implements [ChatTool] for type consistency with [GeoTool]
 * (so `chatToolRegistry.tools() + currentLocationTool` in `ChatAgent.analyzeMessage`
 * types cleanly as `List<ChatTool>`) even though, unlike that one, this
 * class is never a Spring bean and so [ChatToolRegistry] never collects it
 * itself - see that class's doc comment.
 */
class CurrentLocationTool(
    private val geoTool: GeoTool,
    private val browserLatitude: Double?,
    private val browserLongitude: Double?,
) : ChatTool {

    @Tool(
        name = "get_user_location",
        description = "Get the user's own current geographic location, as shared by their browser. " +
            "Call this when the user asks about their own location or surroundings (\"where am I\", " +
            "\"what's near me\") rather than a place they've named. Takes no parameters - never guess " +
            "or invent coordinates yourself.",
    )
    fun getUserCurrentLocation(): String {
        val latitude = browserLatitude
        val longitude = browserLongitude
        return if (latitude != null && longitude != null) {
            geoTool.reverseLookup(latitude, longitude)
        } else {
            """{"error": "The browser did not share the user's location. Ask the user which location they mean."}"""
        }
    }
}

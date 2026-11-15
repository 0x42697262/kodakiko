package dev.kodakiko.markdown

import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.UnsupportedGoldenFunction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MarkdownGoldenAdapter : GoldenAdapter {
    override fun call(fn: String, args: JsonObject): JsonElement = when (fn) {
        "parseMarkdown" -> JsonArray(parseMarkdown(args.string("source")).map(::block))
        "applyMarkdownAction" -> {
            val selection = args.getValue("selection").jsonObject
            val edited = applyMarkdownAction(
                MarkdownAction.entries.first { it.wire == args.string("action") },
                args.string("text"),
                TextSelection(selection.getValue("start").jsonPrimitive.int, selection.getValue("end").jsonPrimitive.int),
                args["time"]?.jsonPrimitive?.content ?: "",
            )
            JsonObject(
                mapOf(
                    "text" to JsonPrimitive(edited.text),
                    "selection" to JsonObject(mapOf("start" to JsonPrimitive(edited.selection.start), "end" to JsonPrimitive(edited.selection.end))),
                ),
            )
        }
        "parse.constants" -> JsonObject(
            mapOf(
                "MARKDOWN_MAX_DEPTH" to JsonPrimitive(MARKDOWN_MAX_DEPTH),
                "MAX_HEADING_LEVEL" to JsonPrimitive(MAX_HEADING_LEVEL),
                "SAFE_LINK_SCHEMES" to JsonArray(SAFE_LINK_SCHEMES.map(::JsonPrimitive)),
                "imagePlaceholder" to JsonPrimitive(MarkdownText.IMAGE_PLACEHOLDER),
            ),
        )
        else -> throw UnsupportedGoldenFunction(fn)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun typed(type: String, vararg fields: Pair<String, JsonElement>): JsonElement = JsonObject(mapOf("type" to JsonPrimitive(type)) + fields)

    private fun inlines(children: List<Inline>): JsonElement = JsonArray(children.map(::inline))

    private fun inline(inline: Inline): JsonElement = when (inline) {
        is Inline.Text -> typed("text", "text" to JsonPrimitive(inline.text))
        is Inline.Strong -> typed("strong", "children" to inlines(inline.children))
        is Inline.Em -> typed("em", "children" to inlines(inline.children))
        is Inline.Del -> typed("del", "children" to inlines(inline.children))
        is Inline.Code -> typed("code", "text" to JsonPrimitive(inline.text))
        is Inline.Link -> typed("link", "href" to JsonPrimitive(inline.href), "children" to inlines(inline.children))
        Inline.Break -> typed("break")
    }

    private fun block(block: Block): JsonElement = when (block) {
        is Block.Heading -> typed("heading", "level" to JsonPrimitive(block.level), "children" to inlines(block.children))
        is Block.Paragraph -> typed("paragraph", "children" to inlines(block.children))
        is Block.Code -> typed("code", "text" to JsonPrimitive(block.text))
        is Block.Quote -> typed("quote", "blocks" to JsonArray(block.blocks.map(::block)))
        is Block.ListBlock -> typed(
            "list",
            "ordered" to JsonPrimitive(block.ordered),
            "start" to JsonPrimitive(block.start),
            "items" to JsonArray(
                block.items.map { item ->
                    JsonObject(mapOf("checked" to (item.checked?.let(::JsonPrimitive) ?: JsonNull), "blocks" to JsonArray(item.blocks.map(::block))))
                },
            ),
        )
        Block.Rule -> typed("rule")
    }
}

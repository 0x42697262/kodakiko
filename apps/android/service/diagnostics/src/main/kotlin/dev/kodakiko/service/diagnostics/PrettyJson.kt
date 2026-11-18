package dev.kodakiko.service.diagnostics

import dev.kodakiko.contract.ContractJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun prettyJson(element: JsonElement, indent: String = DiagnosticsConstants.NO_INDENT): String {
    val inner = indent + DiagnosticsConstants.JSON_INDENT
    val close = DiagnosticsConstants.NEWLINE + indent
    return when {
        element is JsonObject && element.isNotEmpty() -> element.entries.joinToString(
            DiagnosticsConstants.ITEM_SEPARATOR,
            DiagnosticsConstants.OBJECT_OPEN + DiagnosticsConstants.NEWLINE,
            close + DiagnosticsConstants.OBJECT_CLOSE,
        ) { (key, value) -> inner + ContractJson.stringify(JsonPrimitive(key)) + DiagnosticsConstants.KEY_SEPARATOR + prettyJson(value, inner) }
        element is JsonArray && element.isNotEmpty() -> element.joinToString(
            DiagnosticsConstants.ITEM_SEPARATOR,
            DiagnosticsConstants.ARRAY_OPEN + DiagnosticsConstants.NEWLINE,
            close + DiagnosticsConstants.ARRAY_CLOSE,
        ) { inner + prettyJson(it, inner) }
        else -> ContractJson.stringify(element)
    }
}

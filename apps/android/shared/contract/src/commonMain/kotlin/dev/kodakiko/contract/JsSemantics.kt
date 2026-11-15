package dev.kodakiko.contract

import dev.kodakiko.js.jsStringToNumber
import dev.kodakiko.js.jsonStringifyNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun jsToNumber(json: JsonElement): Double =
    when (json) {
        is JsonNull -> 0.0
        is JsonPrimitive -> primitiveToNumber(json)
        is JsonArray -> jsStringToNumber(jsArrayToString(json))
        is JsonObject -> Double.NaN
    }

private fun primitiveToNumber(primitive: JsonPrimitive): Double =
    when {
        primitive.isString -> jsStringToNumber(primitive.content)
        primitive.content == ContractPatterns.JSON_TRUE -> 1.0
        primitive.content == ContractPatterns.JSON_FALSE -> 0.0
        isJsonNumber(primitive) -> primitive.content.toDouble()
        else -> Double.NaN
    }

private fun jsElementToString(json: JsonElement): String =
    when (json) {
        is JsonNull -> ""
        is JsonPrimitive -> if (isJsonNumber(json)) jsonStringifyNumber(json.content.toDouble()) else json.content
        is JsonArray -> jsArrayToString(json)
        is JsonObject -> ContractPatterns.JS_OBJECT_STRING
    }

private fun jsArrayToString(array: JsonArray): String = array.joinToString(ContractPatterns.JS_ARRAY_SEPARATOR) { jsElementToString(it) }

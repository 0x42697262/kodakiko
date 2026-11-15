package dev.kodakiko.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal object JsonCodec {
    fun encode(value: JsonElement): String = Json.encodeToString(JsonElement.serializer(), value)

    fun decode(text: String): JsonElement = Json.parseToJsonElement(text)

    fun encodeBytes(value: JsonElement): ByteArray = encode(value).encodeToByteArray()

    fun decodeBytes(bytes: ByteArray): JsonElement = decode(bytes.decodeToString())
}

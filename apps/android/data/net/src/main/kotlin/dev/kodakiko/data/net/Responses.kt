package dev.kodakiko.data.net

import dev.kodakiko.contract.ContractSchema
import dev.kodakiko.contract.ContractViolation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

interface EmptyBodySchema<T> : ContractSchema<T> {
    fun decodeEmpty(): T
}

object VoidResponse : EmptyBodySchema<Unit> {
    override val name = "void"

    override fun decodeEmpty() = Unit

    override fun decode(json: JsonElement): Unit = throw ContractViolation(name, ResponseText.NOT_EMPTY)

    override fun encode(value: Unit): JsonElement = JsonNull
}

object UnknownResponse : EmptyBodySchema<JsonElement?> {
    override val name = "unknown"

    override fun decodeEmpty(): JsonElement? = null

    override fun decode(json: JsonElement): JsonElement? = json

    override fun encode(value: JsonElement?): JsonElement = value ?: JsonNull
}

internal object ResponseText {
    const val NOT_EMPTY = "expected an empty body"
}

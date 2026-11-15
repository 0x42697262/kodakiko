package dev.kodakiko.contract

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

interface ContractSchema<T> {
    val name: String

    fun decode(json: JsonElement): T

    fun encode(value: T): JsonElement
}

class ContractViolation(val schema: String, message: String) : Exception(message)

internal class CodecSchema<T>(override val name: String, private val codec: Codec<T>) : ContractSchema<T> {
    override fun decode(json: JsonElement): T =
        try {
            codec.decode(json, ContractText.ROOT_PATH)
        } catch (error: InvalidValue) {
            throw ContractViolation(name, ContractText.at(error.path, error.problem))
        }

    override fun encode(value: T): JsonElement = codec.encode(value) ?: JsonNull
}

internal fun <T> schema(name: String, codec: Codec<T>): ContractSchema<T> = CodecSchema(name, codec)

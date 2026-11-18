package dev.kodakiko.contract

import dev.kodakiko.js.jsTrim
import dev.kodakiko.js.jsonStringifyNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

internal class InvalidValue(val path: String, val problem: String) : Exception(problem)

internal interface Codec<T> {
    fun decode(json: JsonElement?, path: String): T

    fun encode(value: T): JsonElement?
}

interface WireEnum {
    val wire: String
}

sealed interface OptionalValue<out T> {
    data object Absent : OptionalValue<Nothing>

    data class Present<T>(val value: T) : OptionalValue<T>
}

internal fun present(json: JsonElement?, path: String): JsonElement = json ?: throw InvalidValue(path, ContractText.REQUIRED)

internal fun isJsonNumber(primitive: JsonPrimitive): Boolean =
    primitive !is JsonNull && !primitive.isString && ContractPatterns.JSON_NUMBER.matches(primitive.content)

internal fun codePointLength(text: String): Int {
    var count = text.length
    var index = 0
    while (index < text.length - 1) {
        if (text[index].isHighSurrogate() && text[index + 1].isLowSurrogate()) {
            count -= 1
            index += 1
        }
        index += 1
    }
    return count
}

internal class StringCodec(
    private val minLength: Int = 0,
    private val maxLength: Int = Int.MAX_VALUE,
    private val pattern: Regex? = null,
    private val prefix: String? = null,
    private val refinement: ((String) -> Boolean)? = null,
) : Codec<String> {
    override fun decode(json: JsonElement?, path: String): String {
        val primitive = present(json, path) as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || !primitive.isString) throw InvalidValue(path, ContractText.EXPECTED_STRING)
        val text = primitive.content
        if (codePointLength(text) < minLength) throw InvalidValue(path, ContractText.tooShort(minLength))
        if (codePointLength(text) > maxLength) throw InvalidValue(path, ContractText.tooLong(maxLength))
        if (pattern != null && !pattern.matches(text)) throw InvalidValue(path, ContractText.INVALID_FORMAT)
        if (prefix != null && !text.startsWith(prefix)) throw InvalidValue(path, ContractText.mustStartWith(prefix))
        if (refinement != null && !refinement.invoke(text)) throw InvalidValue(path, ContractText.INVALID_FORMAT)
        return text
    }

    override fun encode(value: String): JsonElement = JsonPrimitive(value)
}

internal object UrlCodec : Codec<String> {
    private val text = StringCodec()

    override fun decode(json: JsonElement?, path: String): String {
        val trimmed = jsTrim(text.decode(json, path))
        if (!canParseUrl(trimmed)) throw InvalidValue(path, ContractText.INVALID_URL)
        return trimmed.filterNot { it in ContractPatterns.URL_TAB_AND_NEWLINE }
    }

    override fun encode(value: String): JsonElement = JsonPrimitive(value)
}

internal fun numberOf(json: JsonElement?, path: String): Double {
    val primitive = present(json, path) as? JsonPrimitive
    if (primitive == null || !isJsonNumber(primitive)) throw InvalidValue(path, ContractText.EXPECTED_NUMBER)
    return primitive.content.toDouble()
}

internal class IntegerCodec(
    private val minimum: Long = MIN_SAFE_INTEGER,
    private val maximum: Long = MAX_SAFE_INTEGER,
    private val coerce: Boolean = false,
) : Codec<Long> {
    override fun decode(json: JsonElement?, path: String): Long {
        val number = if (coerce) jsToNumber(present(json, path)) else numberOf(json, path)
        if (!number.isFinite()) throw InvalidValue(path, ContractText.EXPECTED_NUMBER)
        if (number != floor(number) || number < MIN_SAFE_INTEGER || number > MAX_SAFE_INTEGER) {
            throw InvalidValue(path, ContractText.EXPECTED_INTEGER)
        }
        val value = number.toLong()
        if (value < minimum) throw InvalidValue(path, ContractText.tooSmall(minimum.toString()))
        if (value > maximum) throw InvalidValue(path, ContractText.tooBig(maximum.toString()))
        return value
    }

    override fun encode(value: Long): JsonElement = JsonPrimitive(value)
}

internal class DoubleCodec(
    private val minimum: Double = -Double.MAX_VALUE,
    private val maximum: Double = Double.MAX_VALUE,
) : Codec<Double> {
    override fun decode(json: JsonElement?, path: String): Double {
        val number = numberOf(json, path)
        if (!number.isFinite()) throw InvalidValue(path, ContractText.EXPECTED_NUMBER)
        if (number < minimum) throw InvalidValue(path, ContractText.tooSmall(jsonStringifyNumber(minimum)))
        if (number > maximum) throw InvalidValue(path, ContractText.tooBig(jsonStringifyNumber(maximum)))
        return number
    }

    override fun encode(value: Double): JsonElement = JsonPrimitive(value)
}

internal object BooleanCodec : Codec<Boolean> {
    override fun decode(json: JsonElement?, path: String): Boolean {
        val primitive = present(json, path) as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || primitive.isString) throw InvalidValue(path, ContractText.EXPECTED_BOOLEAN)
        return when (primitive.content) {
            ContractPatterns.JSON_TRUE -> true
            ContractPatterns.JSON_FALSE -> false
            else -> throw InvalidValue(path, ContractText.EXPECTED_BOOLEAN)
        }
    }

    override fun encode(value: Boolean): JsonElement = JsonPrimitive(value)
}

internal class EnumCodec<E : WireEnum>(private val options: List<E>) : Codec<E> {
    override fun decode(json: JsonElement?, path: String): E {
        val primitive = present(json, path) as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || !primitive.isString) throw InvalidValue(path, ContractText.INVALID_OPTION)
        return options.firstOrNull { it.wire == primitive.content } ?: throw InvalidValue(path, ContractText.INVALID_OPTION)
    }

    override fun encode(value: E): JsonElement = JsonPrimitive(value.wire)
}

internal class StringLiteralCodec(private val literal: String) : Codec<Unit> {
    override fun decode(json: JsonElement?, path: String) {
        val primitive = present(json, path) as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || !primitive.isString || primitive.content != literal) {
            throw InvalidValue(path, ContractText.INVALID_LITERAL)
        }
    }

    override fun encode(value: Unit): JsonElement = JsonPrimitive(literal)
}

internal class NumberLiteralCodec(private val literal: Int) : Codec<Unit> {
    override fun decode(json: JsonElement?, path: String) {
        val primitive = present(json, path) as? JsonPrimitive
        if (primitive == null || !isJsonNumber(primitive) || primitive.content.toDouble() != literal.toDouble()) {
            throw InvalidValue(path, ContractText.INVALID_LITERAL)
        }
    }

    override fun encode(value: Unit): JsonElement = JsonPrimitive(literal)
}

internal object UnknownCodec : Codec<JsonElement> {
    override fun decode(json: JsonElement?, path: String): JsonElement = present(json, path)

    override fun encode(value: JsonElement): JsonElement = value
}

internal class ArrayCodec<T>(
    private val item: Codec<T>,
    private val minItems: Int = 0,
    private val maxItems: Int = Int.MAX_VALUE,
) : Codec<List<T>> {
    override fun decode(json: JsonElement?, path: String): List<T> {
        val array = present(json, path) as? JsonArray ?: throw InvalidValue(path, ContractText.EXPECTED_ARRAY)
        val items = array.mapIndexed { index, element -> item.decode(element, ContractText.item(path, index)) }
        if (items.size < minItems) throw InvalidValue(path, ContractText.tooFewItems(minItems))
        if (items.size > maxItems) throw InvalidValue(path, ContractText.tooManyItems(maxItems))
        return items
    }

    override fun encode(value: List<T>): JsonElement = JsonArray(value.map { item.encode(it) ?: JsonNull })
}

internal class NullableCodec<T : Any>(private val inner: Codec<T>) : Codec<T?> {
    override fun decode(json: JsonElement?, path: String): T? = if (present(json, path) is JsonNull) null else inner.decode(json, path)

    override fun encode(value: T?): JsonElement? = if (value == null) JsonNull else inner.encode(value)
}

internal class OptionalCodec<T : Any>(private val inner: Codec<T>) : Codec<T?> {
    override fun decode(json: JsonElement?, path: String): T? = if (json == null) null else inner.decode(json, path)

    override fun encode(value: T?): JsonElement? = if (value == null) null else inner.encode(value)
}

internal class DefaultNullCodec<T : Any>(inner: Codec<T>) : Codec<T?> {
    private val nullable = NullableCodec(inner)

    override fun decode(json: JsonElement?, path: String): T? = if (json == null) null else nullable.decode(json, path)

    override fun encode(value: T?): JsonElement? = nullable.encode(value)
}

internal class PresenceCodec<T>(private val inner: Codec<T>) : Codec<OptionalValue<T>> {
    override fun decode(json: JsonElement?, path: String): OptionalValue<T> =
        if (json == null) OptionalValue.Absent else OptionalValue.Present(inner.decode(json, path))

    override fun encode(value: OptionalValue<T>): JsonElement? =
        when (value) {
            OptionalValue.Absent -> null
            is OptionalValue.Present -> inner.encode(value.value)
        }
}

internal class RefinedCodec<T>(private val inner: Codec<T>, private val problem: String, private val check: (T) -> Boolean) : Codec<T> {
    override fun decode(json: JsonElement?, path: String): T {
        val value = inner.decode(json, path)
        if (!check(value)) throw InvalidValue(path, problem)
        return value
    }

    override fun encode(value: T): JsonElement? = inner.encode(value)
}

internal class ObjectReader(private val fields: JsonObject, private val path: String) {
    fun <T> field(key: String, codec: Codec<T>): T = codec.decode(fields[key], ContractText.field(path, key))
}

internal class ObjectWriter {
    private val content = LinkedHashMap<String, JsonElement>()

    fun <T> field(key: String, codec: Codec<T>, value: T) {
        codec.encode(value)?.let { content[key] = it }
    }

    fun build(): JsonObject = JsonObject(content)
}

internal class ObjectCodec<T>(
    private val read: ObjectReader.() -> T,
    private val write: ObjectWriter.(T) -> Unit,
) : Codec<T> {
    override fun decode(json: JsonElement?, path: String): T {
        val fields = present(json, path) as? JsonObject ?: throw InvalidValue(path, ContractText.EXPECTED_OBJECT)
        return ObjectReader(fields, path).read()
    }

    override fun encode(value: T): JsonElement = ObjectWriter().apply { write(value) }.build()
}

internal class DiscriminatedCodec<T>(
    private val key: String,
    private val variants: Map<String, Codec<out T>>,
    private val write: (T) -> JsonElement,
) : Codec<T> {
    override fun decode(json: JsonElement?, path: String): T {
        val fields = present(json, path) as? JsonObject ?: throw InvalidValue(path, ContractText.EXPECTED_OBJECT)
        val tag = fields[key] as? JsonPrimitive
        val variant = tag?.takeIf { it !is JsonNull && it.isString }?.let { variants[it.content] }
            ?: throw InvalidValue(ContractText.field(path, key), ContractText.INVALID_DISCRIMINATOR)
        return variant.decode(fields, path)
    }

    override fun encode(value: T): JsonElement = write(value)
}

internal fun <T> Codec<T>.encodeRequired(value: T): JsonElement = encode(value) ?: JsonNull

internal fun <T> objectCodec(read: ObjectReader.() -> T, write: ObjectWriter.(T) -> Unit): Codec<T> = ObjectCodec(read, write)

internal fun <T : Any> Codec<T>.nullable(): Codec<T?> = NullableCodec(this)

internal fun <T : Any> Codec<T>.optional(): Codec<T?> = OptionalCodec(this)

internal fun <T : Any> Codec<T>.defaultNull(): Codec<T?> = DefaultNullCodec(this)

internal fun <T> Codec<T>.presence(): Codec<OptionalValue<T>> = PresenceCodec(this)

internal fun <T> Codec<T>.array(minItems: Int = 0, maxItems: Int = Int.MAX_VALUE): Codec<List<T>> = ArrayCodec(this, minItems, maxItems)

internal fun <T> Codec<T>.refine(problem: String, check: (T) -> Boolean): Codec<T> = RefinedCodec(this, problem, check)

internal fun <E : WireEnum> enumCodec(options: List<E>): Codec<E> = EnumCodec(options)

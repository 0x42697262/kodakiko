package dev.kodakiko.contract

import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.MalformedPayloadError
import dev.kodakiko.crypto.PADDING_BLOCK_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.UnsupportedFormatError
import dev.kodakiko.crypto.openBlob
import dev.kodakiko.crypto.padIso7816
import dev.kodakiko.crypto.sealBlob
import dev.kodakiko.crypto.unpadIso7816
import dev.kodakiko.crypto.utf8Decode
import dev.kodakiko.crypto.utf8Encode
import dev.kodakiko.crypto.wipe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

typealias PayloadMigrations = Map<Int, (JsonObject) -> JsonObject>

private const val SCHEMA_VERSION_KEY = "schemaVersion"

fun <T> sealPayload(sodium: Sodium, key: ByteArray, value: T, schema: ContractSchema<T>, context: AdContext, bindingId: String): ByteArray {
    val plaintext = utf8Encode(ContractJson.stringify(schema.encode(value)))
    val padded = padIso7816(plaintext, PADDING_BLOCK_BYTES)
    try {
        return sealBlob(sodium, key, padded, context, bindingId)
    } finally {
        wipe(plaintext)
        wipe(padded)
    }
}

fun <T> openPayload(
    sodium: Sodium,
    key: ByteArray,
    blob: ByteArray,
    schema: ContractSchema<T>,
    currentVersion: Int,
    migrations: PayloadMigrations,
    context: AdContext,
    bindingId: String,
): T {
    val padded = openBlob(sodium, key, blob, context, bindingId)
    val raw = try {
        parsePayloadObject(utf8Decode(unpadIso7816(padded, PADDING_BLOCK_BYTES)))
    } finally {
        wipe(padded)
    }
    val versionField = raw[SCHEMA_VERSION_KEY] as? JsonPrimitive
    if (versionField == null || !isJsonNumber(versionField)) throw MalformedPayloadError()
    val schemaVersion = versionField.content.toDouble()
    if (!schemaVersion.isFinite() || schemaVersion != floor(schemaVersion)) throw MalformedPayloadError()
    if (schemaVersion > currentVersion) throw UnsupportedFormatError()
    var migrated = raw
    var version = schemaVersion
    while (version < currentVersion) {
        val migrate = (if (version >= Int.MIN_VALUE) migrations[version.toInt()] else null) ?: throw UnsupportedFormatError()
        migrated = migrate(migrated)
        version += 1
    }
    return try {
        schema.decode(migrated)
    } catch (error: ContractViolation) {
        throw MalformedPayloadError()
    }
}

private fun parsePayloadObject(text: String): JsonObject =
    try {
        ContractJson.parse(text) as? JsonObject ?: throw MalformedPayloadError()
    } catch (error: ContractViolation) {
        throw MalformedPayloadError()
    }

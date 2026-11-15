package dev.kodakiko.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.time.TimeSource

private typealias CaseCheck = (Sodium, JsonObject) -> Boolean

private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

private fun JsonObject.hex(field: String): ByteArray = hexToBytes(text(field))

private fun JsonObject.integer(field: String): Int = getValue(field).jsonPrimitive.int

private fun JsonObject.long(field: String): Long = getValue(field).jsonPrimitive.long

private fun JsonObject.texts(field: String): List<String> = getValue(field).jsonArray.map { it.jsonPrimitive.content }

private fun hexEquals(bytes: ByteArray, hex: String): Boolean = bytesToHex(bytes) == hex

private fun typeScriptKey(enumName: String): String = enumName.lowercase().split(ENUM_NAME_SEPARATOR)
    .mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar { it.uppercaseChar() } }
    .joinToString("")

private fun hexByteLength(hex: String): Int = hex.length / HEX_CHARS_PER_BYTE

private val checkArgon2id: CaseCheck = { sodium, case ->
    val output = sodium.argon2id(
        Argon2idInput(
            password = case.hex(VectorFields.PASSWORD),
            salt = case.hex(VectorFields.SALT),
            opsLimit = case.long(VectorFields.OPS_LIMIT),
            memLimitBytes = case.long(VectorFields.MEM_LIMIT_BYTES),
            outputLength = hexByteLength(case.text(VectorFields.OUTPUT)),
        ),
    )
    hexEquals(output, case.text(VectorFields.OUTPUT))
}

private val checkKdf: CaseCheck = { sodium, case ->
    val context = KdfContext.entries.firstOrNull { typeScriptKey(it.name) == case.text(VectorFields.CONTEXT) }
    context != null &&
        hexEquals(
            sodium.kdfDerive(
                hexByteLength(case.text(VectorFields.SUBKEY)),
                case.long(VectorFields.SUBKEY_ID),
                context.value,
                case.hex(VectorFields.KEY),
            ),
            case.text(VectorFields.SUBKEY),
        )
}

private val checkSealedBlob: CaseCheck = { sodium, case ->
    val context = AdContext.entries.firstOrNull { typeScriptKey(it.name) == case.text(VectorFields.CONTEXT_KEY) }
    if (context == null || context.value != case.text(VectorFields.CONTEXT)) {
        false
    } else {
        val seeded = sodium.withRandomSource(createCounterRandom(case.integer(VectorFields.RANDOM_SEED)))
        val key = case.hex(VectorFields.KEY)
        val bindingId = case.text(VectorFields.BINDING_ID)
        val blob = sealBlob(seeded, key, case.hex(VectorFields.PLAINTEXT), context, bindingId)
        val opened = openBlob(sodium, key, case.hex(VectorFields.BLOB), context, bindingId)
        hexEquals(blob, case.text(VectorFields.BLOB)) && hexEquals(opened, case.text(VectorFields.PLAINTEXT))
    }
}

private val checkPadding: CaseCheck = { _, case ->
    val padded = padIso7816(case.hex(VectorFields.INPUT), PADDING_BLOCK_BYTES)
    hexEquals(padded, case.text(VectorFields.PADDED)) &&
        hexEquals(unpadIso7816(case.hex(VectorFields.PADDED), PADDING_BLOCK_BYTES), case.text(VectorFields.INPUT))
}

private val checkStream: CaseCheck = { sodium, case ->
    val plaintext = counterBytes(case.integer(VectorFields.PLAINTEXT_SEED), case.long(VectorFields.PLAINTEXT_SIZE).toInt())
    val seeded = sodium.withRandomSource(createCounterRandom(case.integer(VectorFields.RANDOM_SEED)))
    val key = case.hex(VectorFields.KEY)
    val encryptor = createStreamEncryptor(seeded, key, plaintext.size.toLong())
    val chunkSize = encryptor.layout.chunkSize
    val chunks = (0 until encryptor.layout.chunkCount.toInt()).map { index ->
        val start = minOf(index * chunkSize, plaintext.size)
        encryptor.encryptNext(plaintext.copyOfRange(start, minOf(start + chunkSize, plaintext.size)))
    }
    val digests = chunks.map { bytesToHex(sodium.genericHash(GENERIC_HASH_BYTES, it)) }
    val firstChunk = chunks.firstOrNull() ?: ByteArray(0)
    val prefixLength = minOf(hexByteLength(case.text(VectorFields.FIRST_CHUNK_PREFIX)), firstChunk.size)
    val decryptor = createStreamDecryptor(sodium, key, encryptor.header, plaintext.size.toLong())
    val decrypted = chunks.map { decryptor.decryptNext(it) }
    decryptor.finish()
    bytesEqual(concatBytes(*decrypted.toTypedArray()), plaintext) &&
        hexEquals(encryptor.header, case.text(VectorFields.HEADER)) &&
        encryptor.layout.chunkCount == case.long(VectorFields.CHUNK_COUNT) &&
        encryptor.layout.ciphertextSize == case.long(VectorFields.CIPHERTEXT_SIZE) &&
        digests == case.texts(VectorFields.CHUNK_DIGESTS) &&
        hexEquals(firstChunk.copyOfRange(0, prefixLength), case.text(VectorFields.FIRST_CHUNK_PREFIX))
}

private val checkRecoveryWords: CaseCheck = { sodium, case ->
    val words = case.texts(VectorFields.WORDS)
    encodeRecoveryKey(sodium, case.hex(VectorFields.RECOVERY_KEY)) == words &&
        hexEquals(decodeRecoveryWords(sodium, words), case.text(VectorFields.RECOVERY_KEY))
}

private val checkSharePayload: CaseCheck = { _, case ->
    hexEquals(
        buildSharePayload(case.text(VectorFields.COLLECTION_ID), case.hex(VectorFields.COLLECTION_KEY)),
        case.text(VectorFields.PAYLOAD),
    )
}

private val VECTOR_CHECKS: List<Pair<String, CaseCheck>> = listOf(
    VectorFiles.ARGON2ID to checkArgon2id,
    VectorFiles.KDF to checkKdf,
    VectorFiles.SEALED_BLOB to checkSealedBlob,
    VectorFiles.PADDING to checkPadding,
    VectorFiles.STREAM to checkStream,
    VectorFiles.RECOVERY_WORDS to checkRecoveryWords,
    VectorFiles.SHARE_PAYLOAD to checkSharePayload,
)

private fun runCase(sodium: Sodium, formatSupported: Boolean, case: JsonElement, check: CaseCheck): Boolean {
    if (!formatSupported) return false
    return try {
        check(sodium, case.jsonObject)
    } catch (_: Exception) {
        false
    }
}

private fun runFile(sodium: Sodium, file: String, text: String, check: CaseCheck): List<VectorCaseResult> {
    val vectorFile = Json.parseToJsonElement(text).jsonObject
    val formatSupported = vectorFile[VectorFields.FORMAT]?.jsonPrimitive?.content == VECTOR_FORMAT.toString()
    return vectorFile.getValue(VectorFields.CASES).jsonArray.mapIndexed { index, element ->
        val started = TimeSource.Monotonic.markNow()
        val passed = runCase(sodium, formatSupported, element, check)
        VectorCaseResult(file, "$file$VECTOR_CASE_NAME_SEPARATOR$index", passed, started.elapsedNow().inWholeMilliseconds)
    }
}

fun checkVectors(sodium: Sodium, load: (fileName: String) -> String): List<VectorCaseResult> =
    VECTOR_CHECKS.flatMap { (file, check) -> runFile(sodium, file, load(file), check) }

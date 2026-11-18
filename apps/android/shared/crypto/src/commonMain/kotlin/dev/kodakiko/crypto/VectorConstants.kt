package dev.kodakiko.crypto

internal object VectorFiles {
    const val ARGON2ID = "argon2id.json"
    const val KDF = "kdf.json"
    const val SEALED_BLOB = "sealed-blob.json"
    const val PADDING = "padding.json"
    const val STREAM = "stream.json"
    const val RECOVERY_WORDS = "recovery-words.json"
    const val SHARE_PAYLOAD = "share-payload.json"
}

internal object VectorFields {
    const val FORMAT = "format"
    const val CASES = "cases"
    const val PASSWORD = "password"
    const val SALT = "salt"
    const val OPS_LIMIT = "opsLimit"
    const val MEM_LIMIT_BYTES = "memLimitBytes"
    const val OUTPUT = "output"
    const val CONTEXT = "context"
    const val CONTEXT_KEY = "contextKey"
    const val SUBKEY_ID = "subkeyId"
    const val KEY = "key"
    const val SUBKEY = "subkey"
    const val BINDING_ID = "bindingId"
    const val PLAINTEXT = "plaintext"
    const val RANDOM_SEED = "randomSeed"
    const val BLOB = "blob"
    const val INPUT = "input"
    const val PADDED = "padded"
    const val PLAINTEXT_SIZE = "plaintextSize"
    const val PLAINTEXT_SEED = "plaintextSeed"
    const val HEADER = "header"
    const val CHUNK_COUNT = "chunkCount"
    const val CIPHERTEXT_SIZE = "ciphertextSize"
    const val CHUNK_DIGESTS = "chunkDigests"
    const val FIRST_CHUNK_PREFIX = "firstChunkPrefix"
    const val RECOVERY_KEY = "recoveryKey"
    const val WORDS = "words"
    const val COLLECTION_ID = "collectionId"
    const val COLLECTION_KEY = "collectionKey"
    const val PAYLOAD = "payload"
}

internal const val VECTOR_CASE_NAME_SEPARATOR = "#"
internal const val ENUM_NAME_SEPARATOR = '_'

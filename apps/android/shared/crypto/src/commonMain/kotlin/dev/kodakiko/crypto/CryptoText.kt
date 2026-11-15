package dev.kodakiko.crypto

object CryptoText {
    const val DECRYPTION_FAILED = "The data could not be decrypted."
    const val UNSUPPORTED_FORMAT = "The data uses a format version this app does not support."
    const val MALFORMED_PAYLOAD = "The data is not in the expected format."
    const val MALFORMED_OBJECT = "The stored file is not in the expected format."
    const val TRUNCATED_OBJECT = "The stored file is incomplete."
    const val INVALID_CHUNK = "The chunk has an unexpected size or position."
    const val INVALID_KEY_LENGTH = "The key has an unexpected length."
    const val INVALID_RECOVERY_WORD_COUNT = "The recovery key must have exactly 24 words."
    const val RECOVERY_CHECKSUM_MISMATCH = "The recovery words do not match. Check them and try again."
    const val SHARE_COLLECTION_MISMATCH = "The shared key belongs to a different collection."
    const val SODIUM_CALL_FAILED = "The cryptography library reported an error."

    fun unknownRecoveryWord(position: Int): String = "Word $position is not in the word list."
}

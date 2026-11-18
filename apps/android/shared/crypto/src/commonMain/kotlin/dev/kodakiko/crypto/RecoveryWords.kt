package dev.kodakiko.crypto

import dev.kodakiko.js.normalizeNfkc

private val WORD_INDEX: Map<String, Int> by lazy { WORDLIST_EN.withIndex().associate { (index, word) -> word to index } }

private const val RECOVERY_BYTES = KEY_BYTES + RECOVERY_CHECKSUM_BITS / BYTE_BITS

private fun checksumByte(sodium: Sodium, recoveryKey: ByteArray): Byte {
    val digest = sodium.genericHash(GENERIC_HASH_BYTES, recoveryKey)
    val checksum = digest[0]
    wipe(digest)
    return checksum
}

private fun bitShift(bitPosition: Int): Int = BYTE_BITS - 1 - bitPosition % BYTE_BITS

fun encodeRecoveryKey(sodium: Sodium, recoveryKey: ByteArray): List<String> {
    if (recoveryKey.size != KEY_BYTES) throw InvalidKeyLengthError(CryptoText.INVALID_KEY_LENGTH)
    val bytes = ByteArray(RECOVERY_BYTES)
    try {
        recoveryKey.copyInto(bytes)
        bytes[KEY_BYTES] = checksumByte(sodium, recoveryKey)
        return List(RECOVERY_WORD_COUNT) { wordPosition ->
            var wordIndex = 0
            for (bit in 0 until RECOVERY_WORD_BITS) {
                val bitPosition = wordPosition * RECOVERY_WORD_BITS + bit
                val value = (bytes[bitPosition / BYTE_BITS].toInt() shr bitShift(bitPosition)) and 1
                wordIndex = (wordIndex shl 1) or value
            }
            WORDLIST_EN[wordIndex]
        }
    } finally {
        wipe(bytes)
    }
}

fun normalizeRecoveryInput(input: String): List<String> =
    normalizeNfkc(input).lowercase().split(RECOVERY_INPUT_WHITESPACE).filter { it.isNotEmpty() }

fun decodeRecoveryWords(sodium: Sodium, words: List<String>): ByteArray {
    if (words.size != RECOVERY_WORD_COUNT) throw InvalidRecoveryWordsError(null, CryptoText.INVALID_RECOVERY_WORD_COUNT)
    val bytes = ByteArray(RECOVERY_BYTES)
    try {
        words.forEachIndexed { wordPosition, word ->
            val wordIndex = WORD_INDEX[word]
                ?: throw InvalidRecoveryWordsError(wordPosition, CryptoText.unknownRecoveryWord(wordPosition + 1))
            for (bit in 0 until RECOVERY_WORD_BITS) {
                val value = (wordIndex shr (RECOVERY_WORD_BITS - 1 - bit)) and 1
                val bitPosition = wordPosition * RECOVERY_WORD_BITS + bit
                val byteIndex = bitPosition / BYTE_BITS
                bytes[byteIndex] = (bytes[byteIndex].toInt() or (value shl bitShift(bitPosition))).toByte()
            }
        }
        val recoveryKey = bytes.copyOfRange(0, KEY_BYTES)
        if (bytes[KEY_BYTES] != checksumByte(sodium, recoveryKey)) {
            wipe(recoveryKey)
            throw InvalidRecoveryWordsError(null, CryptoText.RECOVERY_CHECKSUM_MISMATCH)
        }
        return recoveryKey
    } finally {
        wipe(bytes)
    }
}

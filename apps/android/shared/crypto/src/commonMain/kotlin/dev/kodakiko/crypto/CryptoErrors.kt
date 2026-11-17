package dev.kodakiko.crypto

sealed class CryptoException(message: String) : Exception(message)

class DecryptionError(message: String = CryptoText.DECRYPTION_FAILED) : CryptoException(message)

class UnsupportedFormatError(message: String = CryptoText.UNSUPPORTED_FORMAT) : CryptoException(message)

class MalformedPayloadError(message: String = CryptoText.MALFORMED_PAYLOAD) : CryptoException(message)

class MalformedObjectError(message: String = CryptoText.MALFORMED_OBJECT) : CryptoException(message)

class TruncatedObjectError(message: String = CryptoText.TRUNCATED_OBJECT) : CryptoException(message)

class InvalidChunkError(message: String = CryptoText.INVALID_CHUNK) : CryptoException(message)

class ZeroKeyError(message: String = CryptoText.ZERO_KEY) : CryptoException(message)

class InvalidKeyLengthError(message: String = CryptoText.INVALID_KEY_LENGTH) : CryptoException(message)

class InvalidRecoveryWordsError(val wordPosition: Int?, message: String) : CryptoException(message)

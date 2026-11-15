package dev.kodakiko.crypto

internal expect fun normalizeNfkc(text: String): String

fun passwordToBytes(password: String): ByteArray = utf8Encode(normalizeNfkc(password))

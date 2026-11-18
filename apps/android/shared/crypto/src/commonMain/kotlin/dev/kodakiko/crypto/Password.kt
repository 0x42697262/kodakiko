package dev.kodakiko.crypto

import dev.kodakiko.js.normalizeNfkc

fun passwordToBytes(password: String): ByteArray = utf8Encode(normalizeNfkc(password))

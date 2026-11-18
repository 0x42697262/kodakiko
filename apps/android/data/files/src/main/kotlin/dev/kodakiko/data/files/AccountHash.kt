package dev.kodakiko.data.files

import dev.kodakiko.crypto.GENERIC_HASH_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.bytesToHex
import dev.kodakiko.crypto.utf8Encode

fun accountHash(sodium: Sodium, accountKey: String): String =
    bytesToHex(sodium.genericHash(GENERIC_HASH_BYTES, utf8Encode(accountKey))).take(FilesConstants.ACCOUNT_HASH_HEX_CHARS)

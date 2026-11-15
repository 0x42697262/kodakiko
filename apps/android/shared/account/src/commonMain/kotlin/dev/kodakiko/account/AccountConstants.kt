package dev.kodakiko.account

import dev.kodakiko.contract.USERNAME_PATTERN
import dev.kodakiko.crypto.ARGON2ID_MIN_MEM_BYTES as CRYPTO_ARGON2ID_MIN_MEM_BYTES
import dev.kodakiko.crypto.ARGON2ID_MIN_WORK as CRYPTO_ARGON2ID_MIN_WORK
import dev.kodakiko.crypto.ARGON2ID_START_MEM_BYTES as CRYPTO_ARGON2ID_START_MEM_BYTES
import dev.kodakiko.crypto.ARGON2ID_START_OPS as CRYPTO_ARGON2ID_START_OPS
import dev.kodakiko.crypto.SALT_BYTES as CRYPTO_SALT_BYTES

const val SALT_BYTES: Int = CRYPTO_SALT_BYTES
const val ARGON2ID_START_OPS: Long = CRYPTO_ARGON2ID_START_OPS
const val ARGON2ID_START_MEM_BYTES: Long = CRYPTO_ARGON2ID_START_MEM_BYTES
const val ARGON2ID_MIN_MEM_BYTES: Long = CRYPTO_ARGON2ID_MIN_MEM_BYTES
const val ARGON2ID_MIN_WORK: Long = CRYPTO_ARGON2ID_MIN_WORK
const val PASSWORD_MIN_CODE_POINTS = 12
const val ARGON2ID_BUDGET_MS = 8_000L
const val KEK_BYTES = 32

internal const val ADAPTIVE_FACTOR = 2L
internal const val SURROGATE_PAIR_LENGTH = 2

internal val USERNAME_REGEX = Regex(USERNAME_PATTERN)

internal val JS_WHITESPACE: Set<Char> = setOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u00A0', '\u1680',
    '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A',
    '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
)

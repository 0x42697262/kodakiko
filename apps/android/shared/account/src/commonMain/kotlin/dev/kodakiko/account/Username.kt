package dev.kodakiko.account

import dev.kodakiko.js.jsTrim
import dev.kodakiko.js.normalizeNfkc

fun normalizeUsername(input: String): String = jsTrim(normalizeNfkc(input)).lowercase()

fun isValidUsername(normalized: String): Boolean = USERNAME_REGEX.matches(normalized)

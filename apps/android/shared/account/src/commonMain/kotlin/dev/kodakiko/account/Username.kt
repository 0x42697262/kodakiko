package dev.kodakiko.account

fun normalizeUsername(input: String): String = normalizeNfkc(input).trim { it in JS_WHITESPACE }.lowercase()

fun isValidUsername(normalized: String): Boolean = USERNAME_REGEX.matches(normalized)

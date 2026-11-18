package dev.kodakiko.index

import dev.kodakiko.js.normalizeNfkc

fun tokenize(text: String): List<String> {
    val tokens = LinkedHashSet<String>()
    for (part in normalizeNfkc(text).lowercase().split(TOKEN_SEPARATOR_PATTERN)) {
        if (part.length < TOKEN_MIN_CHARS) continue
        tokens.add(part)
        if (tokens.size >= MAX_INDEX_TOKENS_PER_ENTRY) break
    }
    return tokens.toList()
}

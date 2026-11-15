package dev.kodakiko.media

fun isJsWhitespace(char: Char): Boolean = char in JS_WHITESPACE

fun jsTrim(text: String): String = text.trim { isJsWhitespace(it) }

fun collapseJsWhitespace(text: String, replacement: String): String {
    val builder = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        if (isJsWhitespace(text[index])) {
            while (index < text.length && isJsWhitespace(text[index])) index += 1
            builder.append(replacement)
        } else {
            builder.append(text[index])
            index += 1
        }
    }
    return builder.toString()
}

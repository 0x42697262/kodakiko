package dev.kodakiko.markdown

internal class MarkedEdit(private var source: String, private val flags: String = "") {
    fun replace(name: String, value: String): MarkedEdit {
        val position = source.indexOf(name)
        if (position >= 0) source = source.substring(0, position) + expandReplacement(stripCaret(value), name, position) + source.substring(position + name.length)
        return this
    }

    fun replaceAll(name: String, value: String): MarkedEdit {
        val inserted = stripCaret(value)
        val builder = StringBuilder()
        var from = 0
        while (true) {
            val position = source.indexOf(name, from)
            if (position < 0) break
            builder.append(source, from, position).append(expandReplacement(inserted, name, position))
            from = position + name.length
        }
        source = builder.append(source, from, source.length).toString()
        return this
    }

    fun source(): String = source

    fun regex(): Regex = jsRegex(source, flags)

    private fun stripCaret(value: String): String = MarkedRules.CARET.replace(value) { it.groupValues[1] }

    private fun expandReplacement(value: String, matched: String, position: Int): String {
        if (MarkedRules.DOLLAR !in value) return value
        val builder = StringBuilder()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val next = value.getOrNull(index + 1)
            when {
                char != MarkedRules.DOLLAR || next == null -> builder.append(char)
                next == MarkedRules.DOLLAR -> builder.append(MarkedRules.DOLLAR).also { index += 1 }
                next == MarkedRules.MATCH_REFERENCE -> builder.append(matched).also { index += 1 }
                next == MarkedRules.BEFORE_REFERENCE -> builder.append(source, 0, position).also { index += 1 }
                next == MarkedRules.AFTER_REFERENCE -> builder.append(source, position + matched.length, source.length).also { index += 1 }
                else -> builder.append(char)
            }
            index += 1
        }
        return builder.toString()
    }
}

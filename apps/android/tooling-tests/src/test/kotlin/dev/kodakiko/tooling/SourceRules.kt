package dev.kodakiko.tooling

import java.io.File

object Repo {
    val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
            ?: error("repository root not found from ${File("").absolutePath}")
    }

    val android: File get() = File(root, "apps/android")

    private val skippedDirectories = setOf("build", ".gradle", ".kotlin", "resources")

    fun kotlinFiles(): List<File> =
        android.walkTopDown()
            .onEnter { it.name !in skippedDirectories }
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".kts")) }
            .toList()

    fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath
}

data class Violation(val path: String, val line: Int, val rule: String, val text: String) {
    override fun toString(): String = "$path:$line [$rule] $text"
}

object SourceRules {
    val ATTRIBUTION_HEADER = listOf(
        "// Adapted from https://github.com/mihonapp/mihon",
        "// Copyright 2015 Javier Tom\u00e1s, 2024 Mihon Open Source Project, Apache-2.0",
    )

    fun comments(path: String, text: String): List<Violation> {
        val lines = text.lines()
        val headerPresent = lines.size >= 2 && lines[0] == ATTRIBUTION_HEADER[0] && lines[1] == ATTRIBUTION_HEADER[1]
        return KotlinLexer.lex(text).comments
            .filterNot { headerPresent && it.line <= 2 && it.text == ATTRIBUTION_HEADER[it.line - 1] }
            .map { Violation(path, it.line, "NoComments", it.text.lineSequence().first().take(80)) }
    }

    fun magicNumberScope(path: String): Boolean =
        path.endsWith(".kt") &&
            Regex("/src/([A-Za-z]*Main|main)/").containsMatchIn(path) &&
            !path.contains("/build-logic/") &&
            !path.substringAfterLast('/').endsWith("Constants.kt")

    private const val PROPERTY_LOOKBEHIND = 200

    private val uiTextCalls = setOf("Text", "BasicText", "testTag")

    private val uiTextProperties = Regex("""\b(contentDescription|testTag)\s*=\s*$""")

    fun uiLiteralScope(path: String): Boolean =
        path.endsWith(".kt") && Regex("""^apps/android/ui/.+/src/main/""").containsMatchIn(path)

    private fun masked(text: String, strings: List<StringLiteral>): String {
        val chars = text.toCharArray()
        for (literal in strings.filterNot { it.nested }) {
            for (offset in literal.start until literal.end) if (chars[offset] != '\n') chars[offset] = '_'
        }
        return String(chars)
    }

    private fun enclosingCall(code: String, from: Int): String? {
        var depth = 0
        var index = from - 1
        while (index >= 0) {
            when (code[index]) {
                ')', ']', '}' -> depth++
                '[', '{' -> if (depth == 0) return null else depth--
                '(' -> if (depth == 0) {
                    val before = code.substring(0, index).trimEnd()
                    return Regex("""([A-Za-z_][A-Za-z0-9_]*)$""").find(before)?.groupValues?.get(1)
                } else {
                    depth--
                }
            }
            index--
        }
        return null
    }

    fun uiLiterals(path: String, text: String): List<Violation> {
        if (!uiLiteralScope(path)) return emptyList()
        val strings = KotlinLexer.lex(text).strings
        val code = masked(text, strings)
        return strings.filterNot { it.nested }.filter { literal ->
            uiTextProperties.containsMatchIn(code.substring(maxOf(0, literal.start - PROPERTY_LOOKBEHIND), literal.start)) || enclosingCall(code, literal.start) in uiTextCalls
        }.map { Violation(path, it.line, "UiLiteral", text.substring(it.start, it.end).lineSequence().first().take(80)) }
    }

    fun magicNumbers(path: String, text: String): List<Violation> =
        if (!magicNumberScope(path)) {
            emptyList()
        } else {
            KotlinLexer.lex(text).numbers
                .filterNot { KotlinLexer.numericValueIsTrivial(it.text) }
                .map { Violation(path, it.line, "MagicNumber", it.text) }
        }
}

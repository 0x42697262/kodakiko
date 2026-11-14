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

    fun magicNumbers(path: String, text: String): List<Violation> =
        if (!magicNumberScope(path)) {
            emptyList()
        } else {
            KotlinLexer.lex(text).numbers
                .filterNot { KotlinLexer.numericValueIsTrivial(it.text) }
                .map { Violation(path, it.line, "MagicNumber", it.text) }
        }
}

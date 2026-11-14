package dev.kodakiko.tooling

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceRulesTest {
    private val main = "apps/android/shared/crypto/src/commonMain/kotlin/dev/kodakiko/crypto/Stream.kt"

    @Test
    fun repositoryHasNoExplanatoryComments() {
        val violations = Repo.kotlinFiles().flatMap { SourceRules.comments(Repo.relative(it), it.readText()) }
        violations.map { it.toString() }.shouldBeEmpty()
    }

    @Test
    fun repositoryHasNoMagicNumbersInMainSources() {
        val violations = Repo.kotlinFiles().flatMap { SourceRules.magicNumbers(Repo.relative(it), it.readText()) }
        violations.map { it.toString() }.shouldBeEmpty()
    }

    @Test
    fun lineAndBlockAndDocCommentsAreFound() {
        val code = """
            val a = 0
            // note
            /* block */
            /** doc */
            /* outer /* nested */ still */
            val b = 1
        """.trimIndent()
        SourceRules.comments(main, code).map { it.line } shouldBe listOf(2, 3, 4, 5)
    }

    @Test
    fun commentMarkersInsideStringsAreNotComments() {
        val code = "val url = \"https://example.com/*x*/\"\n" +
            "val raw = \"\"\"// not a comment \${\"//\"} \"\"\"\n" +
            "val c = '/'\n" +
            "val t = \"\${listOf(\"/*\").size} //\"\n"
        SourceRules.comments(main, code).shouldBeEmpty()
    }

    @Test
    fun codeInsideTemplatesIsStillScanned() {
        val code = "val t = \"\${run { 42 }}\"\n"
        SourceRules.magicNumbers(main, code) shouldHaveSize 1
    }

    @Test
    fun attributionHeaderIsAllowedOnlyAsTheFirstTwoLines() {
        val header = SourceRules.ATTRIBUTION_HEADER.joinToString("\n")
        SourceRules.comments(main, "$header\npackage x\n").shouldBeEmpty()
        SourceRules.comments(main, "package x\n$header\n") shouldHaveSize 2
        SourceRules.comments(main, "${SourceRules.ATTRIBUTION_HEADER[0]}\npackage x\n") shouldHaveSize 1
        SourceRules.comments(main, "$header\n// extra\npackage x\n") shouldHaveSize 1
    }

    @Test
    fun magicNumbersAreFlaggedOnlyInMainSourcesOutsideConstants() {
        val code = "val a = 0\nval b = 1L\nval c = 2\nval d = 0xff\nval e = 1.5f\nval f = 1_000\nval g = 0.0\nval range = 1..1\nval id = x2y\n"
        SourceRules.magicNumbers(main, code).map { it.text } shouldBe listOf("2", "0xff", "1.5f", "1_000")
        SourceRules.magicNumbers(main.replace("Stream.kt", "StreamConstants.kt"), code).shouldBeEmpty()
        SourceRules.magicNumbers(main.replace("commonMain", "commonTest"), code).shouldBeEmpty()
        SourceRules.magicNumbers("apps/android/app/build.gradle.kts", code).shouldBeEmpty()
        SourceRules.magicNumbers("apps/android/build-logic/src/main/kotlin/X.kt", code).shouldBeEmpty()
        SourceRules.magicNumbers("apps/android/app/src/main/kotlin/dev/kodakiko/app/MainActivity.kt", code) shouldHaveSize 4
    }
}

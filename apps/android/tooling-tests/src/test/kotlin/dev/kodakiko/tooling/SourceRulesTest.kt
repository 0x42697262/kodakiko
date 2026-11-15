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

    private val ui = "apps/android/ui/feature/gallery/src/main/kotlin/dev/kodakiko/ui/feature/gallery/GalleryContent.kt"

    @Test
    fun repositoryHasNoLiteralUiTextInUiSources() {
        val violations = Repo.kotlinFiles().flatMap { SourceRules.uiLiterals(Repo.relative(it), it.readText()) }
        violations.map { it.toString() }.shouldBeEmpty()
    }

    @Test
    fun literalTextDescriptionsAndTagsAreFlaggedInUiSources() {
        val code = listOf(
            "Text(\"Hello\")",
            "Text(text = \"Hello\")",
            "Text(modifier = Modifier.padding(4.dp), text = \"Hi\")",
            "Text(\n    \"Wrapped\",\n)",
            "Text(\"Count: \$count\")",
            "Text(\"Total \" + total)",
            "Text(\"\"\"raw\"\"\")",
            "Text(if (on) \"On\" else stringResource(R.string.off))",
            "BasicText(\"basic\")",
            "Icon(Icons.Default.Close, contentDescription = \"Close\")",
            "Box(Modifier.semantics { contentDescription = \"Photo\" })",
            "Box(Modifier.testTag(\"gallery.tile\"))",
            "Box(Modifier.semantics { testTag = \"gallery.screen\" })",
            "Text(stringResource(R.string.ok), modifier = Modifier.testTag(\"ok\"))",
        ).joinToString("\n")
        SourceRules.uiLiterals(ui, code).map { it.line } shouldBe listOf(1, 2, 3, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        SourceRules.uiLiterals(ui, code).map { it.text } shouldBe listOf(
            "\"Hello\"", "\"Hello\"", "\"Hi\"", "\"Wrapped\"", "\"Count: \$count\"", "\"Total \"", "\"\"\"raw\"\"\"", "\"On\"",
            "\"basic\"", "\"Close\"", "\"Photo\"", "\"gallery.tile\"", "\"gallery.screen\"", "\"ok\"",
        )
    }

    @Test
    fun resourcesTestTagsAndOtherLiteralsAreAllowedInUiSources() {
        val code = listOf(
            "Text(stringResource(R.string.app_loading))",
            "Text(text = pluralStringResource(R.plurals.shared_entry_count, count, count))",
            "Text(stringResource(R.string.gallery_search_chip, \"x\"))",
            "Text(text = name, modifier = Modifier.testTag(TestTags.GALLERY_TILE))",
            "Text(formatBytes(size))",
            "Text(items.joinToString(\", \"))",
            "Icon(Icons.Default.Close, contentDescription = null)",
            "Icon(Icons.Default.Close, contentDescription = stringResource(R.string.capture_close))",
            "Box(Modifier.testTag(TestTags.listItem(TestTags.GALLERY_TILE, index)))",
            "val route = \"gallery\"",
            "val shown = \"Text(\\\"x\\\") contentDescription = testTag(\"",
            "Text(text = buildAnnotatedString { append(\"#\") })",
            "Text(text = s, style = TextStyle(fontFeatureSettings = \"tnum\"))",
        ).joinToString("\n")
        SourceRules.uiLiterals(ui, code).map { it.toString() }.shouldBeEmpty()
    }

    @Test
    fun uiLiteralRuleCoversOnlyUiMainSources() {
        val code = "Text(\"Hello\")\nBox(Modifier.testTag(\"x\"))\n"
        SourceRules.uiLiterals(ui, code) shouldHaveSize 2
        SourceRules.uiLiterals("apps/android/ui/core/src/main/kotlin/dev/kodakiko/ui/core/components/AppBar.kt", code) shouldHaveSize 2
        SourceRules.uiLiterals(ui.replace("/src/main/", "/src/test/"), code).shouldBeEmpty()
        SourceRules.uiLiterals("apps/android/service/sync/src/main/kotlin/dev/kodakiko/service/sync/SyncBanner.kt", code).shouldBeEmpty()
        SourceRules.uiLiterals("apps/android/ui/core/build.gradle.kts", code).shouldBeEmpty()
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

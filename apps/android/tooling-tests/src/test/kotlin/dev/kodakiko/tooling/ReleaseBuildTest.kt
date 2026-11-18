package dev.kodakiko.tooling

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

class ReleaseBuildTest {
    private val appBuild = File(Repo.android, "app/build.gradle.kts").readText()
    private val rootBuild = File(Repo.android, "build.gradle.kts").readText()
    private val catalog = File(Repo.android, "gradle/libs.versions.toml").readText()
    private val buildLogicConstants =
        File(Repo.android, "build-logic/src/main/kotlin/dev/kodakiko/buildlogic/BuildLogicConstants.kt").readText()
    private val expoConfig = File(Repo.root, "tests/fixtures/v031-device/app.config.v031.txt").readText()
    private val workflow = File(Repo.root, ".github/workflows/android-release.yml").readText()

    private fun capture(text: String, pattern: String): String =
        Regex(pattern).find(text)?.groupValues?.get(1) ?: error("not found: $pattern")

    private fun block(text: String, header: String): String? {
        val start = Regex("""(?m)^\s*$header\s*\{""").find(text) ?: return null
        var depth = 0
        for (index in start.range.last until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start.range.last + 1, index)
            }
        }
        error("unclosed block $header")
    }

    private fun expoVersionCode(): Int {
        val range = capture(expoConfig, """const VERSION_PART_RANGE = (\d+);""").toInt()
        return capture(expoConfig, """const APP_VERSION = '([\d.]+)';""")
            .split('.').fold(0) { code, part -> code * range + part.toInt() }
    }

    private fun weight(name: String): Int = capture(buildLogicConstants, """const val $name = ([\d_]+)""").replace("_", "").toInt()

    private fun kotlinVersionName(): String = capture(catalog, """(?m)^app-version-name = "([\d.]+)"$""")

    private fun kotlinVersionCode(): Int {
        val (major, minor, patch) = kotlinVersionName().split('.').map { it.toInt() }
        return major * weight("VERSION_CODE_MAJOR_WEIGHT") + minor * weight("VERSION_CODE_MINOR_WEIGHT") + patch
    }

    @Test
    fun releaseKeepsTheV031PackageAndRaisesTheVersionCode() {
        val base = capture(appBuild, """applicationId = "([\w.]+)"""")
        base shouldBe "com.x42697262.kodakiko"
        capture(expoConfig, """package: '([\w.]+)'""") shouldBe base
        expoVersionCode() shouldBe 301
        kotlinVersionCode() shouldBeGreaterThan expoVersionCode()
        block(appBuild, "release")?.let { release ->
            listOf("applicationIdSuffix", "versionNameSuffix", "versionCode", "isDebuggable").filter { it in release }.shouldBeEmpty()
        }
    }

    @Test
    fun previewIsAReleaseLikeNextBuildSignedWithTheDebugKey() {
        val preview = block(appBuild, """create\("preview"\)""") ?: error("app/build.gradle.kts has no preview build type")
        preview shouldContain """initWith(getByName("release"))"""
        preview shouldContain """applicationIdSuffix = ".next""""
        preview shouldContain """signingConfig = signingConfigs.getByName("debug")"""
        preview shouldContain """matchingFallbacks += "release""""
        listOf("isDebuggable", "isMinifyEnabled", "isShrinkResources", "isJniDebuggable", "proguardFiles", "manifestPlaceholders")
            .filter { it in preview }.shouldBeEmpty()
        appBuild shouldNotContain "previewImplementation"
        listOf("app/src/preview/AndroidManifest.xml", "app/src/preview/res/xml")
            .filter { File(Repo.android, it).exists() }.shouldBeEmpty()
        rootBuild shouldContain "\":app:testPreviewUnitTest\""
    }

    @Test
    fun releaseWorkflowBuildsAndSignsTheKotlinApp() {
        workflow shouldContain "apps/android/gradle/libs.versions.toml"
        workflow shouldContain "app-version-name"
        workflow shouldContain "./gradlew verify"
        workflow shouldContain "pnpm verify"
        workflow shouldContain ":app:assembleRelease"
        workflow shouldContain "apps/android/app/build/outputs/apk/release/app-release.apk"
        listOf("store.file", "store.password", "key.alias", "key.password")
            .filterNot { "-Pandroid.injected.signing.$it=" in workflow }.shouldBeEmpty()
        workflow shouldContain "CN=Android Debug"
        workflow shouldContain "'apps/android/**'"
        listOf("apps/client", "prebuild:android", "reactNativeArchitectures", "NDK_VERSION").filter { it in workflow }.shouldBeEmpty()
    }
}

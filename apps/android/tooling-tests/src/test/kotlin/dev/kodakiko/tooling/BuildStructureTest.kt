package dev.kodakiko.tooling

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BuildStructureTest {
    private val settings = File(Repo.android, "settings.gradle.kts").readText()

    private val modules: List<String> =
        Regex("""include\(\s*"(:[\w:-]+)"\s*\)""").findAll(settings).map { it.groupValues[1] }.toList()

    private fun buildFile(module: String) = File(Repo.android, module.removePrefix(":").replace(':', '/') + "/build.gradle.kts")

    private val layerOf = mapOf("shared" to 0, "data" to 1, "platform" to 1, "service" to 2, "ui" to 3, "app" to 4)

    private fun layer(module: String): Int? = layerOf[module.removePrefix(":").substringBefore(':')]

    private fun referencedModules(text: String): List<String> =
        Regex("""projects\.([\w.]+)""").findAll(text).map { ":" + it.groupValues[1].replace('.', ':') }.toList() +
            Regex("""project\(\s*"(:[\w:-]+)"\s*\)""").findAll(text).map { it.groupValues[1] }.toList()

    @Test
    fun settingsUseOnlyTheAllowedRepositories() {
        settings shouldContain "RepositoriesMode.FAIL_ON_PROJECT_REPOS"
        val calls = Regex("""\b(google|mavenCentral|gradlePluginPortal|mavenLocal|jcenter|maven|ivy)\s*\(""")
            .findAll(settings).map { it.groupValues[1] }.toSet()
        (calls - setOf("google", "mavenCentral", "gradlePluginPortal")).shouldBeEmpty()
    }

    @Test
    fun m1ModulesExist() {
        modules shouldContainAll listOf(":app", ":tooling-tests", ":shared:crypto", ":data:db")
        modules.filterNot { buildFile(it).isFile }.shouldBeEmpty()
    }

    @Test
    fun modulesApplyTheirConventionPlugin() {
        val expected = mapOf(
            "shared" to "kodakiko.kmp.library",
            "data" to "kodakiko.android.library",
            "platform" to "kodakiko.android.library",
            "service" to "kodakiko.android.library",
            "ui" to "kodakiko.android.library",
            "app" to "kodakiko.android.application",
            "tooling-tests" to "kodakiko.jvm.tooling",
            "e2e" to "kodakiko.android.library",
        )
        val wrong = modules.mapNotNull { module ->
            val plugin = expected.getValue(module.removePrefix(":").substringBefore(':'))
            if (buildFile(module).readText().contains("id(\"$plugin\")")) null else "$module must apply id(\"$plugin\")"
        }
        wrong.shouldBeEmpty()
    }

    @Test
    fun buildFilesUseTheCatalogOnly() {
        val files = modules.map { buildFile(it) } + File(Repo.android, "build-logic/build.gradle.kts") + File(Repo.android, "build.gradle.kts")
        val literals = files.filter { it.isFile }.flatMap { file ->
            Regex(""""[\w.-]+:[\w.-]+:\d[^"]*"""").findAll(file.readText()).map { "${Repo.relative(file)}: ${it.value}" }
        }
        literals.shouldBeEmpty()
        files.filter { it.isFile && it.readText().contains(Regex("""\brepositories\s*\{""")) && !it.path.contains("build-logic") }
            .map { Repo.relative(it) }.shouldBeEmpty()
    }

    @Test
    fun layersOnlyDependDownwardAndFeaturesNeverOnFeatures() {
        val broken = modules.flatMap { module ->
            val own = layer(module) ?: return@flatMap emptyList()
            referencedModules(buildFile(module).readText()).mapNotNull { dependency ->
                val other = layer(dependency) ?: return@mapNotNull null
                when {
                    other > own -> "$module -> $dependency (upward)"
                    own == other && own == 0 -> null
                    module.startsWith(":ui:feature:") && dependency.startsWith(":ui:feature:") -> "$module -> $dependency (feature to feature)"
                    own == other && own in 1..2 && module.substringBeforeLast(':') != dependency.substringBeforeLast(':') -> null
                    else -> null
                }
            }
        }
        broken.shouldBeEmpty()
    }

    @Test
    fun wrapperIsPinnedWithChecksum() {
        val properties = File(Repo.android, "gradle/wrapper/gradle-wrapper.properties").readText()
        properties shouldContain "gradle-9.9.0-bin.zip"
        properties shouldContain "distributionSha256Sum="
    }

    @Test
    fun dependencyVerificationAndLockingAreOn() {
        val metadata = File(Repo.android, "gradle/verification-metadata.xml").readText()
        metadata shouldContain "<verify-checksums>true</verify-checksums>"
        metadata shouldContain "sha256"
        modules.filterNot { File(buildFile(it).parentFile, "gradle.lockfile").isFile }.shouldBeEmpty()
    }

    private fun xml(path: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(Repo.android, path)).documentElement

    @Test
    fun releaseNetworkConfigForbidsCleartextAndDebugAllowsOnlyLocalHosts() {
        val release = xml("app/src/main/res/xml/network_security_config.xml")
        val base = release.getElementsByTagName("base-config").item(0) as org.w3c.dom.Element
        base.getAttribute("cleartextTrafficPermitted") shouldBe "false"
        release.getElementsByTagName("domain-config").length shouldBe 0
        val debug = xml("app/src/debug/res/xml/network_security_config.xml")
        val domains = (0 until debug.getElementsByTagName("domain").length)
            .map { debug.getElementsByTagName("domain").item(it).textContent.trim() }.toSet()
        domains shouldBe setOf("localhost", "127.0.0.1", "10.0.2.2")
        (debug.getElementsByTagName("domain-config").item(0) as org.w3c.dom.Element)
            .getAttribute("cleartextTrafficPermitted") shouldBe "true"
        (debug.getElementsByTagName("base-config").item(0) as org.w3c.dom.Element)
            .getAttribute("cleartextTrafficPermitted") shouldBe "false"
        debug.getElementsByTagName("debug-overrides").length shouldBe 1
    }

    @Test
    fun backupAndDeviceTransferExcludeEverything() {
        val rules = xml("app/src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.getElementsByTagName(section).item(0) as org.w3c.dom.Element
            val excluded = (0 until element.getElementsByTagName("exclude").length)
                .map { (element.getElementsByTagName("exclude").item(it) as org.w3c.dom.Element).getAttribute("domain") }.toSet()
            excluded shouldBe setOf(
                "root", "file", "database", "sharedpref", "external",
                "device_root", "device_file", "device_database", "device_sharedpref",
            )
        }
        File(Repo.android, "app/src/main/AndroidManifest.xml").readText().let {
            it shouldContain "android:allowBackup=\"false\""
            it shouldContain "android:dataExtractionRules=\"@xml/data_extraction_rules\""
            it shouldContain "android:fullBackupContent=\"false\""
            it shouldContain "android:enableOnBackInvokedCallback=\"true\""
            it shouldNotContain "android:usesCleartextTraffic=\"true\""
        }
    }
}

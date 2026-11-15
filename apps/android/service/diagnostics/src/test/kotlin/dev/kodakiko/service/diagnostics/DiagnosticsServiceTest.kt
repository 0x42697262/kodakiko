@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.contract.API_VERSION
import dev.kodakiko.contract.ContractJson
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.VectorCaseResult
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.DeviceSettingsJson
import dev.kodakiko.data.secure.AccountMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

private val VECTOR_FILES = setOf("argon2id.json", "kdf.json", "sealed-blob.json", "padding.json", "stream.json", "recovery-words.json", "share-payload.json")

private val CONFIG = PublicConfigDto(
    signupMode = SignupMode.INVITE,
    sharingEnabled = true,
    publicLinksEnabled = true,
    maxMediaPerEntry = 20,
    maxUploadBytes = 3_758_096_384,
    defaultQuotaBytes = 0,
    trashDays = 30,
    accountDeletionGraceDays = 7,
    serverVersion = "0.3.1",
)

private val repoRoot: File by lazy {
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
}

private fun jsPrettyJson(element: JsonElement, indent: String = ""): String {
    val inner = "$indent  "
    return when (element) {
        is JsonObject ->
            if (element.isEmpty()) {
                "{}"
            } else {
                element.entries.joinToString(",\n", "{\n", "\n$indent}") { (key, value) -> inner + ContractJson.stringify(JsonPrimitive(key)) + ": " + jsPrettyJson(value, inner) }
            }
        is JsonArray -> if (element.isEmpty()) "[]" else element.joinToString(",\n", "[\n", "\n$indent]") { inner + jsPrettyJson(it, inner) }
        else -> ContractJson.stringify(element)
    }
}

@RunWith(RobolectricTestRunner::class)
class DiagnosticsServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sodium: Sodium = createSodium()

    private var config: PublicConfigDto? = CONFIG
    private var mode: AccountMode = AccountMode.SERVER
    private var settings: DeviceSettings = DeviceSettingsDefaults.ANDROID

    private fun service(io: CoroutineDispatcher, vectors: VectorSource = AssetVectorSource(context), appVersion: String? = "0.4.0-test", osVersion: Int = 36): DiagnosticsService =
        DefaultDiagnosticsService(
            DiagnosticsDeps(
                sodium = sodium,
                vectors = vectors,
                appVersion = appVersion,
                osVersion = osVersion,
                config = { config },
                mode = { mode },
                settings = { settings },
                ioDispatcher = io,
            ),
        )

    private fun TestScope.unconfined(): CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)

    @Test
    fun theBundledVectorsAreTheCoreVectorFiles() {
        val source = AssetVectorSource(context)
        for (name in VECTOR_FILES) {
            assertEquals(name, File(repoRoot, "packages/core/vectors/$name").readText(), source.read(name))
        }
    }

    @Test
    fun allThirtyEightBundledVectorsPassOnThisDevice() = runTest {
        val report: VectorReport = service(unconfined()).runVectors()

        assertEquals(38, report.total)
        assertEquals(38, report.passed)
        assertEquals(38, report.cases.size)
        assertEquals(VECTOR_FILES, report.cases.map { it.file }.toSet())
        assertTrue(report.cases.filterNot { it.passed }.joinToString { it.name }, report.cases.all { it.passed })
        assertEquals(report.cases.map { it.name }.distinct(), report.cases.map { it.name })
    }

    @Test
    fun aTamperedVectorIsReportedAsOneFailureWithoutThrowing() = runTest {
        val bundled = AssetVectorSource(context)
        val tampered = VectorSource { name: String ->
            val text = bundled.read(name)
            if (name == "kdf.json") text.replaceFirst("\"subkey\": \"4d", "\"subkey\": \"00") else text
        }

        val report: VectorReport = service(unconfined(), vectors = tampered).runVectors()

        assertEquals(38, report.total)
        assertEquals(37, report.passed)
        val failed: VectorCaseResult = report.cases.single { !it.passed }
        assertEquals("kdf.json", failed.file)
    }

    @Test
    fun vectorsRunOnTheInjectedIoDispatcher() = runTest {
        val threads = CopyOnWriteArrayList<String>()
        val bundled = AssetVectorSource(context)
        val probing = VectorSource { name: String ->
            threads += Thread.currentThread().name
            bundled.read(name)
        }
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "diagnostics-io-test") }
        val io: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
        val report = try {
            service(io, vectors = probing).runVectors()
        } finally {
            io.close()
        }
        assertEquals(38, report.passed)
        assertEquals(VECTOR_FILES.size, threads.size)
        assertTrue(threads.all { it == "diagnostics-io-test" })
    }

    @Test
    fun debugInfoHasVersionsModeAndSettingsInJavaScriptPrettyJson() = runTest {
        settings = DeviceSettingsDefaults.ANDROID.copy(tagOrder = listOf("beach", "family \"trip\""), autoLockMinutes = 15)

        val text: String = service(unconfined()).debugInfoJson()

        val expected = JsonObject(
            linkedMapOf(
                "app" to JsonPrimitive("0.4.0-test"),
                "platform" to JsonPrimitive("android"),
                "os" to JsonPrimitive(36),
                "server" to JsonPrimitive("0.3.1"),
                "api" to JsonPrimitive(API_VERSION),
                "mode" to JsonPrimitive("server"),
                "settings" to DeviceSettingsJson.encode(settings),
            ),
        )
        assertEquals(expected, Json.parseToJsonElement(text))
        assertEquals(expected.keys.toList(), (Json.parseToJsonElement(text) as JsonObject).keys.toList())
        assertEquals(jsPrettyJson(expected), text)
        assertTrue(text.startsWith("{\n  \"app\": \"0.4.0-test\",\n  \"platform\": \"android\",\n  \"os\": 36,\n  \"server\": \"0.3.1\",\n  \"api\": 1,\n  \"mode\": \"server\",\n  \"settings\": {\n    \""))
        assertTrue(text.contains("\n    \"tagOrder\": [\n      \"beach\",\n      \"family \\\"trip\\\"\"\n    ],\n"))
        assertTrue(text.endsWith("\n  }\n}"))
    }

    @Test
    fun debugInfoWithoutServerConfigOrAppVersionUsesNullsAndTheLocalMode() = runTest {
        config = null
        mode = AccountMode.LOCAL

        val text: String = service(unconfined(), appVersion = null, osVersion = 26).debugInfoJson()

        val parsed = Json.parseToJsonElement(text) as JsonObject
        assertEquals(JsonNull, parsed["app"])
        assertEquals(JsonPrimitive(26), parsed["os"])
        assertEquals(JsonNull, parsed["server"])
        assertEquals(JsonNull, parsed["api"])
        assertEquals(JsonPrimitive("local"), parsed["mode"])
        assertEquals(DeviceSettingsJson.encode(DeviceSettingsDefaults.ANDROID), parsed["settings"])
        assertEquals(jsPrettyJson(parsed), text)
    }

    @Test
    fun debugInfoReadsTheCurrentValuesOnEveryCall() = runTest {
        val diagnostics = service(unconfined())
        val before = Json.parseToJsonElement(diagnostics.debugInfoJson()) as JsonObject
        settings = settings.copy(syncPaused = !settings.syncPaused)
        mode = AccountMode.LOCAL
        val after = Json.parseToJsonElement(diagnostics.debugInfoJson()) as JsonObject
        assertEquals(JsonPrimitive("server"), before["mode"])
        assertEquals(JsonPrimitive("local"), after["mode"])
        assertEquals(DeviceSettingsJson.encode(settings), after["settings"])
    }

    @Test
    fun debugInfoContainsNoJournalContentOrSecretsBeyondSettings() = runTest {
        val parsed = Json.parseToJsonElement(service(unconfined()).debugInfoJson()) as JsonObject
        assertEquals(setOf("app", "platform", "os", "server", "api", "mode", "settings"), parsed.keys)
    }
}

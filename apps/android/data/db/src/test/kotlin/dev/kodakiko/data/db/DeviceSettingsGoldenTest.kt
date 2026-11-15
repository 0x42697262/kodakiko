package dev.kodakiko.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.GoldenReplay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceSettingsGoldenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var counter = 0

    private fun freshDatabase(): KodakikoDatabase = KodakikoDatabase.open(context, "%016d".format(++counter))

    private fun storeRaw(database: KodakikoDatabase, raw: JsonElement) {
        if (raw is JsonObject && raw["\$absent"] != null) return
        val bytes = (raw as? JsonObject)?.get("\$bytes")?.jsonPrimitive?.content?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
        database.kvSet(StorageKeys.SETTINGS_DEVICE, bytes ?: raw.toString().toByteArray())
    }

    private fun storedJson(database: KodakikoDatabase): JsonElement =
        database.kvGet(StorageKeys.SETTINGS_DEVICE)?.let { Json.parseToJsonElement(String(it)) } ?: JsonNull

    private val adapter = GoldenAdapter { fn, args ->
        when (fn) {
            "nativeDefaults" -> DeviceSettingsJson.encode(DeviceSettingsDefaults.ANDROID)
            "resolveStored" -> {
                val database = freshDatabase()
                try {
                    storeRaw(database, args.getValue("raw"))
                    val invalid = mutableListOf<String>()
                    val store = DeviceSettingsStore.open(database, DeviceSettingsDefaults.ANDROID) { invalid += it }
                    JsonObject(
                        mapOf(
                            "value" to DeviceSettingsJson.encode(store.get()),
                            "invalid" to JsonArray(invalid.map(::JsonPrimitive)),
                            "loaded" to JsonPrimitive(store.loaded()),
                        ),
                    )
                } finally {
                    database.close()
                }
            }
            "update" -> {
                val database = freshDatabase()
                try {
                    storeRaw(database, args.getValue("raw"))
                    val store = DeviceSettingsStore.open(database, DeviceSettingsDefaults.ANDROID) { }
                    var notified = 0
                    store.subscribe { notified++ }
                    val rejected = try {
                        store.update(args.getValue("partial").jsonObject)
                        false
                    } catch (error: IllegalArgumentException) {
                        true
                    }
                    JsonObject(
                        mapOf(
                            "rejected" to JsonPrimitive(rejected),
                            "value" to DeviceSettingsJson.encode(store.get()),
                            "stored" to storedJson(database),
                            "notified" to JsonPrimitive(notified),
                        ),
                    )
                } finally {
                    database.close()
                }
            }
            else -> throw dev.kodakiko.testing.UnsupportedGoldenFunction(fn)
        }
    }

    @Test
    fun deviceSettingsBehaveLikeV031() = GoldenReplay.runClient("settings/device.json", adapter)

    @Test
    fun storedJsonUsesTheV031KeyAndSchemaVersion() {
        val database = freshDatabase()
        try {
            DeviceSettingsStore.open(database, DeviceSettingsDefaults.ANDROID) { }.update(JsonObject(mapOf("pureBlack" to JsonPrimitive(true))))
            val stored = storedJson(database).jsonObject
            assertEquals(JsonPrimitive(1), stored["schemaVersion"])
            assertEquals(JsonPrimitive(true), stored["pureBlack"])
        } finally {
            database.close()
        }
    }
}

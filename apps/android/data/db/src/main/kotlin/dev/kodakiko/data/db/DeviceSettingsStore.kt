package dev.kodakiko.data.db

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DeviceSettingsStore private constructor(
    private val database: KodakikoDatabase,
    initial: DeviceSettings,
    initiallyLoaded: Boolean,
) {
    private val lock = Any()
    private val listeners = LinkedHashSet<() -> Unit>()

    @Volatile
    private var current: DeviceSettings = initial

    @Volatile
    private var loaded: Boolean = initiallyLoaded

    fun get(): DeviceSettings = current

    fun loaded(): Boolean = loaded

    fun subscribe(listener: () -> Unit): () -> Unit {
        synchronized(lock) { listeners += listener }
        return { synchronized(lock) { listeners -= listener } }
    }

    fun update(partial: JsonObject) {
        val notify = synchronized(lock) {
            val merged = LinkedHashMap<String, JsonElement>()
            merged[DeviceSettingsKeys.SCHEMA_VERSION] = JsonPrimitive(DeviceSettingsConstants.SCHEMA_VERSION)
            merged.putAll(DeviceSettingsJson.encode(current))
            merged.putAll(partial)
            val next = DeviceSettingsJson.decode(JsonObject(merged))
            database.kvSet(StorageKeys.SETTINGS_DEVICE, DeviceSettingsJson.encodeStoredBytes(next))
            current = next
            loaded = true
            listeners.toList()
        }
        notify.forEach { it() }
    }

    companion object {
        fun open(
            database: KodakikoDatabase,
            defaults: DeviceSettings,
            onInvalidFields: (List<String>) -> Unit = {},
        ): DeviceSettingsStore {
            val read = readStored(database)
            val resolved = DeviceSettingsJson.resolve(read.getOrNull(), defaults)
            if (resolved.invalid.isNotEmpty()) onInvalidFields(resolved.invalid)
            return DeviceSettingsStore(database, resolved.value, read.isSuccess)
        }

        private fun readStored(database: KodakikoDatabase): Result<JsonElement?> = try {
            Result.success(database.kvGet(StorageKeys.SETTINGS_DEVICE)?.let(JsJson::parseBytes))
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
}

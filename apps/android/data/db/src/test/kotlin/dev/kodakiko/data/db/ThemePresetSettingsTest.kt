package dev.kodakiko.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemePresetSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val defaults = DeviceSettingsDefaults.ANDROID
    private var counter = 0

    private fun withDatabase(block: (KodakikoDatabase) -> Unit) {
        val database = KodakikoDatabase.open(context, "%016d".format(++counter))
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun storedWithout(key: String): JsonObject = JsonObject(DeviceSettingsJson.encodeStored(defaults.copy(pureBlack = true)).filterKeys { it != key })

    @Test
    fun theDefaultPresetIsDefault() {
        assertEquals(ThemePreset.DEFAULT, defaults.themePreset)
        assertEquals(JsonPrimitive("default"), DeviceSettingsJson.encode(defaults)[DeviceSettingsKeys.THEME_PRESET])
    }

    @Test
    fun everyPresetRoundTripsThroughTheStoredJsonByItsWireName() {
        for (preset in ThemePreset.entries) {
            val stored = DeviceSettingsJson.encodeStored(defaults.copy(themePreset = preset))
            assertEquals(JsonPrimitive(preset.wire), stored[DeviceSettingsKeys.THEME_PRESET])
            assertEquals(defaults.copy(themePreset = preset), DeviceSettingsJson.decode(stored))
            assertEquals(defaults.copy(themePreset = preset), DeviceSettingsJson.resolve(stored, defaults).value)
        }
    }

    @Test
    fun settingsSavedBeforeThePresetExistedLoadTheDefaultPresetWithoutComplaint() {
        val resolved = DeviceSettingsJson.resolve(storedWithout(DeviceSettingsKeys.THEME_PRESET), defaults)
        assertEquals(defaults.copy(pureBlack = true), resolved.value)
        assertEquals(emptyList<String>(), resolved.invalid)
    }

    @Test
    fun anUnknownPresetIsReportedAndLoadsTheDefault() {
        val raw = JsonObject(DeviceSettingsJson.encodeStored(defaults.copy(pureBlack = true)) + (DeviceSettingsKeys.THEME_PRESET to JsonPrimitive("neon")))
        val resolved = DeviceSettingsJson.resolve(raw, defaults)
        assertEquals(defaults.copy(pureBlack = true), resolved.value)
        assertEquals(listOf(DeviceSettingsKeys.THEME_PRESET), resolved.invalid)
    }

    @Test
    fun theStoreKeepsAChosenPresetAcrossReopeningAndOldStoredSettingsOpenWithTheDefault() {
        withDatabase { database ->
            database.kvSet(StorageKeys.SETTINGS_DEVICE, storedWithout(DeviceSettingsKeys.THEME_PRESET).toString().toByteArray())
            val invalid = mutableListOf<String>()
            val store = DeviceSettingsStore.open(database, defaults) { invalid += it }
            assertEquals(ThemePreset.DEFAULT, store.get().themePreset)
            assertEquals(true, store.get().pureBlack)
            assertEquals(emptyList<String>(), invalid)
            store.update(JsonObject(mapOf(DeviceSettingsKeys.THEME_PRESET to JsonPrimitive(ThemePreset.TOKYO_NIGHT.wire))))
            assertEquals(ThemePreset.TOKYO_NIGHT, DeviceSettingsStore.open(database, defaults).get().themePreset)
            assertEquals(true, DeviceSettingsStore.open(database, defaults).get().pureBlack)
        }
    }
}

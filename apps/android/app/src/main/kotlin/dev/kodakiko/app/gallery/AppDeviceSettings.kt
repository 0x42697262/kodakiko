package dev.kodakiko.app.gallery

import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.DeviceSettingsJson
import dev.kodakiko.data.db.DeviceSettingsStore
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.service.account.DatabaseRowStore
import dev.kodakiko.ui.feature.gallery.GallerySettings
import dev.kodakiko.ui.feature.settings.SettingsStore
import dev.kodakiko.ui.feature.tags.TagsSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppDeviceSettings(
    private val rowStore: DatabaseRowStore,
    private val scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : GallerySettings, TagsSettings, SettingsStore {
    private val writer = ioDispatcher.limitedParallelism(1)
    private val state = MutableStateFlow(DeviceSettingsDefaults.ANDROID)

    private val loadLock = Any()

    @Volatile
    private var store: DeviceSettingsStore? = null

    @Volatile
    private var loadedFrom: KodakikoDatabase? = null

    override val settings: StateFlow<DeviceSettings> = state.asStateFlow()

    fun load() {
        synchronized(loadLock) {
            val database = rowStore.current() ?: return
            if (store != null && loadedFrom === database) return
            val opened = DeviceSettingsStore.open(database, DeviceSettingsDefaults.ANDROID)
            store = opened
            loadedFrom = database
            state.value = opened.get()
        }
    }

    fun loaded(): Boolean = store != null

    fun unload() {
        synchronized(loadLock) {
            store = null
            loadedFrom = null
            state.value = DeviceSettingsDefaults.ANDROID
        }
    }

    override fun update(change: (DeviceSettings) -> DeviceSettings) {
        var next = state.value
        state.update { current -> change(current).also { next = it } }
        val target = store ?: return
        scope.launch(writer) {
            try {
                target.update(DeviceSettingsJson.encode(next))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }
}

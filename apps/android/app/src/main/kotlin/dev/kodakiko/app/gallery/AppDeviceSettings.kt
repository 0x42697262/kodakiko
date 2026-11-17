package dev.kodakiko.app.gallery

import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.DeviceSettingsJson
import dev.kodakiko.data.db.DeviceSettingsStore
import dev.kodakiko.service.account.DatabaseRowStore
import dev.kodakiko.ui.feature.gallery.GallerySettings
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
) : GallerySettings {
    private val writer = ioDispatcher.limitedParallelism(1)
    private val state = MutableStateFlow(DeviceSettingsDefaults.ANDROID)

    @Volatile
    private var store: DeviceSettingsStore? = null

    override val settings: StateFlow<DeviceSettings> = state.asStateFlow()

    fun load() {
        val database = rowStore.current() ?: return
        val opened = DeviceSettingsStore.open(database, DeviceSettingsDefaults.ANDROID)
        store = opened
        state.value = opened.get()
    }

    fun loaded(): Boolean = store != null

    fun unload() {
        store = null
        state.value = DeviceSettingsDefaults.ANDROID
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

package dev.kodakiko.service.sync

import dev.kodakiko.data.db.DeviceSettings
import java.util.concurrent.CopyOnWriteArrayList

class WifiUploadPolicyDeps(
    val network: WifiSource,
    val settings: () -> DeviceSettings,
    val initialWifi: Boolean,
)

class WifiUploadPolicy(private val deps: WifiUploadPolicyDeps) : UploadPolicy {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var wifi: Boolean = deps.initialWifi

    init {
        deps.network.subscribe(::refresh)
        refresh()
    }

    override fun allowed(sizeBytes: Long): Boolean {
        val settings = deps.settings()
        return sizeBytes <= settings.largeUploadThresholdBytes || !settings.largeUploadsOnWifiOnly || wifi
    }

    fun setWifi(next: Boolean) {
        synchronized(lock) {
            if (next == wifi) return
            wifi = next
        }
        listeners.forEach { it() }
    }

    fun onChange(listener: () -> Unit): () -> Unit {
        val entry: () -> Unit = { listener() }
        listeners += entry
        return { listeners.remove(entry) }
    }

    private fun refresh() {
        val next = try {
            deps.network.isWifi()
        } catch (_: Exception) {
            return
        }
        setWifi(next)
    }
}

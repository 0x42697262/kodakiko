package dev.kodakiko.service.sync

import dev.kodakiko.data.db.DeviceSettingsDefaults
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiUploadPolicyTest {
    @Test
    fun holdsLargeUploadsOffWifiWhenTheSettingIsOnAndReleasesThemOnWifi() {
        var wifi = false
        val listeners = mutableListOf<() -> Unit>()
        val settings = DeviceSettingsDefaults.ANDROID.copy(largeUploadsOnWifiOnly = true, largeUploadThresholdBytes = 104_857_600)
        val policy = WifiUploadPolicy(
            WifiUploadPolicyDeps(
                network = object : WifiSource {
                    override fun isWifi(): Boolean = wifi

                    override fun subscribe(listener: () -> Unit): () -> Unit {
                        listeners += listener
                        return {}
                    }
                },
                settings = { settings },
                initialWifi = false,
            ),
        )
        val large = 120L * 1_048_576
        assertFalse(policy.allowed(large))
        assertTrue(policy.allowed(1_000))
        wifi = true
        policy.setWifi(true)
        assertTrue(policy.allowed(large))
    }
}

package dev.kodakiko.platform.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.domain.DeviceLocation
import dev.kodakiko.media.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LocationProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(LocationManager::class.java)
    private val pool = Executors.newSingleThreadExecutor()

    private fun location(provider: String, latitude: Double, longitude: Double, accuracy: Float?) = Location(provider).apply {
        this.latitude = latitude
        this.longitude = longitude
        accuracy?.let { this.accuracy = it }
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
    }

    private fun <T> await(future: Future<T>, limitMs: Long = 10_000): T {
        val deadline = System.nanoTime() + limitMs * 1_000_000
        while (!future.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return future.get(1, TimeUnit.SECONDS)
    }

    private fun grant() = shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun enableOnly(vararg providers: String) {
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            shadowOf(manager).setProviderEnabled(provider, provider in providers)
        }
        shadowOf(manager).setLocationEnabled(providers.isNotEmpty())
    }

    @Before
    fun setUp() = enableOnly(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    @Test
    fun withoutPermissionThereIsNoPosition() {
        val started = System.nanoTime()
        assertNull(await(pool.submit<DeviceLocation?> { LocationProvider(context).currentPosition(5_000) }))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun aDeliveredFixBecomesTheDevicePosition() {
        grant()
        val future = pool.submit<DeviceLocation?> { LocationProvider(context).currentPosition(5_000) }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(manager).simulateLocation(location(LocationManager.NETWORK_PROVIDER, 48.8584, 2.2945, 25f))
        assertEquals(DeviceLocation(GeoPoint(48.8584, 2.2945), 25.0), await(future))
    }

    @Test
    fun aFixWithoutAccuracyHasNullAccuracy() {
        grant()
        val future = pool.submit<DeviceLocation?> { LocationProvider(context).currentPosition(5_000) }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(manager).simulateLocation(location(LocationManager.NETWORK_PROVIDER, -33.8568, 151.2153, null))
        assertEquals(DeviceLocation(GeoPoint(-33.8568, 151.2153), null), await(future))
    }

    @Test
    fun noFixWithinTheTimeoutIsNull() {
        grant()
        val started = System.nanoTime()
        assertNull(await(pool.submit<DeviceLocation?> { LocationProvider(context).currentPosition(300) }))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs in 250..3_000)
    }

    @Test
    fun noEnabledProviderIsNullAtOnce() {
        grant()
        enableOnly()
        val started = System.nanoTime()
        assertNull(await(pool.submit<DeviceLocation?> { LocationProvider(context).currentPosition(5_000) }))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun theV031TimeoutIsFiveSeconds() {
        assertEquals(5_000L, LocationConstants.TIMEOUT_MS)
    }
}

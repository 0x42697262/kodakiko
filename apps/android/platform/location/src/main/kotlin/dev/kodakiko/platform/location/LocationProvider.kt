package dev.kodakiko.platform.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.kodakiko.domain.DeviceLocation
import dev.kodakiko.media.GeoPoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LocationProvider(context: Context) {
    private val appContext = context.applicationContext

    fun currentPosition(timeoutMs: Long): DeviceLocation? = try {
        if (Looper.myLooper() == Looper.getMainLooper()) null else requestPosition(timeoutMs)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        null
    }

    private fun requestPosition(timeoutMs: Long): DeviceLocation? {
        val permitted = LOCATION_PERMISSIONS.any {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permitted) return null
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return null
        if (!LocationManagerCompat.isLocationEnabled(manager)) return null
        val provider = candidateProviders().firstOrNull(manager::isProviderEnabled) ?: return null
        val fix = AtomicReference<Location?>()
        val delivered = CountDownLatch(1)
        val cancellation = CancellationSignal()
        try {
            LocationManagerCompat.getCurrentLocation(manager, provider, cancellation, ContextCompat.getMainExecutor(appContext)) {
                fix.set(it)
                delivered.countDown()
            }
            if (!delivered.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        } finally {
            cancellation.cancel()
        }
        return fix.get()?.toDeviceLocation()
    }

    private fun candidateProviders(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
    }

    private fun Location.toDeviceLocation() = DeviceLocation(
        point = GeoPoint(latitude, longitude),
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
    )

    private companion object {
        val LOCATION_PERMISSIONS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

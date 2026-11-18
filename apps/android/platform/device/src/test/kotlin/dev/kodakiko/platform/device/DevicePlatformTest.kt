package dev.kodakiko.platform.device

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DevicePlatformTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun hapticsOn(view: View, enabled: () -> Boolean) = Haptics({ view }, enabled)

    @Test
    fun hapticsMapEveryKindLikeV031AndRespectTheSetting() {
        val view = activity().window.decorView
        var enabled = true
        val haptics = hapticsOn(view) { enabled }
        val expected = mapOf(
            HapticKind.SELECT to HapticFeedbackConstants.LONG_PRESS,
            HapticKind.TOGGLE to HapticFeedbackConstants.TOGGLE_ON,
            HapticKind.SHUTTER to HapticFeedbackConstants.VIRTUAL_KEY,
            HapticKind.RECORD_START to HapticFeedbackConstants.GESTURE_START,
            HapticKind.RECORD_STOP to HapticFeedbackConstants.GESTURE_END,
            HapticKind.REFRESH to HapticFeedbackConstants.CONFIRM,
        )
        for ((kind, constant) in expected) {
            haptics.tick(kind)
            assertEquals(kind.name, constant, shadowOf(view).lastHapticFeedbackPerformed())
        }
        haptics.tick(HapticKind.SELECT)
        enabled = false
        haptics.tick(HapticKind.SHUTTER)
        assertEquals(HapticFeedbackConstants.LONG_PRESS, shadowOf(view).lastHapticFeedbackPerformed())
    }

    @Test
    @Config(sdk = [28])
    fun hapticsFallBackToConstantsTheOldPlatformHas() {
        val view = activity().window.decorView
        val haptics = hapticsOn(view) { true }
        haptics.tick(HapticKind.TOGGLE)
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, shadowOf(view).lastHapticFeedbackPerformed())
        haptics.tick(HapticKind.REFRESH)
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, shadowOf(view).lastHapticFeedbackPerformed())
        haptics.tick(HapticKind.RECORD_START)
        assertEquals(HapticFeedbackConstants.LONG_PRESS, shadowOf(view).lastHapticFeedbackPerformed())
    }

    @Test
    fun aMissingViewIsIgnored() {
        Haptics({ null }, { true }).tick(HapticKind.SELECT)
    }

    @Test
    fun screenSecuritySetsAndClearsFlagSecure() {
        val window = activity().window
        ScreenSecurity.apply(window, secure = true)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        ScreenSecurity.apply(window, secure = false)
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun keepScreenOnSetsAndClearsTheFlag() {
        val window = activity().window
        KeepScreenOn.apply(window, on = true)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        KeepScreenOn.apply(window, on = false)
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun systemBarsFollowTheThemeAndDrawEdgeToEdge() {
        val window = activity().window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        SystemBars.apply(window, darkTheme = false)
        assertTrue(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
        SystemBars.apply(window, darkTheme = true)
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)
        SystemBars.hide(window)
        SystemBars.show(window)
    }

    @Test
    fun foregroundTrackingReportsChangesAndTheLastBackgroundTime() {
        var now = 1_000L
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val tracker = ForegroundTracker(owner.lifecycle) { now }
        val seen = mutableListOf<Boolean>()
        val unsubscribe = tracker.subscribe { seen += it }
        assertFalse(tracker.isForeground())
        assertNull(tracker.lastBackgroundAt())
        owner.registry.currentState = Lifecycle.State.STARTED
        now = 5_000
        owner.registry.currentState = Lifecycle.State.CREATED
        assertEquals(5_000L, tracker.lastBackgroundAt())
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(tracker.isForeground())
        unsubscribe()
        owner.registry.currentState = Lifecycle.State.CREATED
        assertEquals(listOf(true, false, true), seen)
    }

    @Test
    @Config(sdk = [34])
    fun notificationPermissionIsNeededOnlyFromApi33UntilGranted() {
        assertTrue(NotificationPermission.needsRequest(context))
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(NotificationPermission.needsRequest(context))
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, NotificationPermission.PERMISSION)
    }

    @Test
    @Config(sdk = [32])
    fun notificationPermissionIsNeverNeededBeforeApi33() {
        assertFalse(NotificationPermission.needsRequest(context))
    }

    @Test
    fun clipboardImagesAreDetectedAndReadAsJpeg() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val images = ClipboardImages(context)
        assertFalse(images.hasImage())
        assertNull(images.readJpeg())
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "hello"))
        assertFalse(images.hasImage())
        assertNull(images.readJpeg())

        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val uri = Uri.parse("content://dev.kodakiko.test/clip.png")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(png))
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(png))
        clipboard.setPrimaryClip(ClipData("image", arrayOf("image/png"), ClipData.Item(uri)))
        assertTrue(images.hasImage())
        val jpeg = images.readJpeg()!!
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), jpeg.copyOfRange(0, 2))
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(40, decoded.width)
        assertEquals(30, decoded.height)
    }
}

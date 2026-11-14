package dev.kodakiko.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppIdentityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val allowedPermissions = setOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.USE_BIOMETRIC",
        "android.permission.USE_FINGERPRINT",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.WAKE_LOCK",
        "com.x42697262.kodakiko.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        "com.x42697262.kodakiko.next.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )

    private val forbiddenPermissions = setOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.VIBRATE",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
    )

    @Test
    fun applicationIdDependsOnBuildType() {
        val expected = if (BuildConfig.BUILD_TYPE == "release") "com.x42697262.kodakiko" else "com.x42697262.kodakiko.next"
        assertEquals(expected, app.packageName)
    }

    @Test
    fun versionIsFourHundred() {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        assertEquals(400L, info.longVersionCode)
        assertEquals("0.4.0", info.versionName)
    }

    @Test
    fun backupIsOff() {
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun permissionsStayInsideTheV031Set() {
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue("not allowed: ${requested - allowedPermissions}", (requested - allowedPermissions).isEmpty())
        assertTrue("forbidden: ${requested intersect forbiddenPermissions}", (requested intersect forbiddenPermissions).isEmpty())
    }

    @Test
    fun mainActivityShowsTheComposeRoot() {
        compose.onNodeWithTag("app.root").assertExists()
    }
}

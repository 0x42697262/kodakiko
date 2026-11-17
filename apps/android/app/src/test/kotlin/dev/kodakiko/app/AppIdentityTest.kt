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
import org.xmlpull.v1.XmlPullParser

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

    private val releaseLike = BuildConfig.BUILD_TYPE in setOf("release", "preview")

    private class NetworkConfig(
        val baseCleartext: String?,
        val cleartextDomains: Set<String>,
        val userCertificatesOutsideDebugOverrides: Int,
    )

    private fun networkConfig(): NetworkConfig {
        val parser = app.resources.getXml(R.xml.network_security_config)
        val open = ArrayDeque<String>()
        var baseCleartext: String? = null
        val cleartextDomains = mutableSetOf<String>()
        var userCertificates = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "base-config" -> baseCleartext = parser.getAttributeValue(null, "cleartextTrafficPermitted")
                        "certificates" -> if (parser.getAttributeValue(null, "src") == "user" && "debug-overrides" !in open) userCertificates++
                        "domain" -> if ("domain-config" in open) cleartextDomains += parser.nextText().trim()
                    }
                    if (parser.eventType == XmlPullParser.START_TAG) open.addLast(parser.name)
                }
                XmlPullParser.END_TAG -> open.removeLastOrNull()
            }
        }
        return NetworkConfig(baseCleartext, cleartextDomains, userCertificates)
    }

    @Test
    fun applicationIdDependsOnBuildType() {
        val base = "com.x42697262.kodakiko"
        val expected = if (BuildConfig.BUILD_TYPE == "release") base else "$base.next"
        assertEquals(expected, app.packageName)
        assertTrue(BuildConfig.BUILD_TYPE, BuildConfig.BUILD_TYPE in setOf("debug", "release", "preview"))
    }

    @Test
    fun onlyTheDebugBuildIsDebuggable() {
        assertEquals(!releaseLike, BuildConfig.DEBUG)
        assertEquals(!releaseLike, app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }

    @Test
    fun releaseLikeBuildsAllowNoCleartextAndNoUserCertificates() {
        val config = networkConfig()
        assertEquals("false", config.baseCleartext)
        assertEquals(0, config.userCertificatesOutsideDebugOverrides)
        if (releaseLike) {
            assertTrue("cleartext domains: ${config.cleartextDomains}", config.cleartextDomains.isEmpty())
        } else {
            assertEquals(setOf("localhost", "127.0.0.1", "10.0.2.2"), config.cleartextDomains)
        }
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

package dev.kodakiko.ui.feature.lock

import android.content.pm.ActivityInfo
import android.os.Looper
import androidx.biometric.BiometricManager.Authenticators
import androidx.fragment.app.FragmentActivity
import dev.kodakiko.data.secure.DeviceGateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
class AndroidxPromptLauncherTest {
    private val results = mutableListOf<PromptResult>()

    @Before
    fun registerFragmentActivity() {
        shadowOf(context.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = FragmentActivity::class.java.name
                packageName = context.packageName
            },
        )
    }

    private fun cipherRequest() = PromptRequest(DeviceGateReason.UNLOCK, Cipher.getInstance("AES/GCM/NoPadding"))

    @Test
    fun aCipherPromptIsStrongBiometricOnlyWithACancelButton() {
        val info = AndroidxPromptLauncher(resources) { null }.promptInfo(cipherRequest())
        assertEquals("Unlock Kodakiko", info.title.toString())
        assertEquals("Cancel", info.negativeButtonText.toString())
        assertEquals(Authenticators.BIOMETRIC_STRONG, info.allowedAuthenticators)
        assertFalse(info.isDeviceCredentialAllowed)
    }

    @Test
    fun aPlainPromptAlsoAcceptsTheScreenLock() {
        val info = AndroidxPromptLauncher(resources) { null }.promptInfo(PromptRequest(DeviceGateReason.UNLOCK, cipher = null))
        assertEquals("Unlock Kodakiko", info.title.toString())
        assertEquals(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL, info.allowedAuthenticators)
        assertTrue(info.negativeButtonText.isNullOrEmpty())
    }

    @Test
    fun theLegacyPickupUsesTheSameStrongPrompt() {
        val info = AndroidxPromptLauncher(resources) { null }
            .promptInfo(PromptRequest(DeviceGateReason.LEGACY_UNLOCK, Cipher.getInstance("AES/GCM/NoPadding")))
        assertEquals(Authenticators.BIOMETRIC_STRONG, info.allowedAuthenticators)
    }

    @Test
    fun withoutAnActivityThePromptFailsAtOnce() {
        AndroidxPromptLauncher(resources) { null }.launch(cipherRequest()) { results += it }
        assertEquals(listOf<PromptResult>(PromptResult.Failed), results)
    }

    @Test
    fun aFinishingActivityCannotHostThePrompt() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        activity.finish()
        AndroidxPromptLauncher(resources) { activity }.launch(cipherRequest()) { results += it }
        assertEquals(listOf<PromptResult>(PromptResult.Failed), results)
        assertTrue(activity.supportFragmentManager.fragments.isEmpty())
    }

    @Test
    fun aResumedActivityHostsThePrompt() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        AndroidxPromptLauncher(resources) { activity }.launch(PromptRequest(DeviceGateReason.UNLOCK, cipher = null)) { results += it }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.supportFragmentManager.fragments.isNotEmpty())
        assertTrue(results.isEmpty())
    }
}

package dev.kodakiko.app.security

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.app.sharing.AppLinkSharing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LinkCopySensitiveTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun copiedExtras(): Pair<String, PersistableBundle?> {
        val launched = mutableListOf<Intent>()
        AppLinkSharing(context) { launched += it }.copy(LINK)
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        assertNotNull("nothing was copied", clip)
        assertEquals(emptyList<Intent>(), launched)
        return clip!!.getItemAt(0).text.toString() to clip.description.extras
    }

    @Test
    @Config(sdk = [ANDROID_16])
    fun aCopiedLinkIsMarkedSensitiveWithTheFrameworkExtra() {
        val (text, extras) = copiedExtras()
        assertEquals(LINK, text)
        assertTrue("extras: $extras", extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
    }

    @Test
    @Config(sdk = [BELOW_TIRAMISU])
    fun belowAndroid13TheLinkCarriesTheLiteralSensitiveExtra() {
        val (text, extras) = copiedExtras()
        assertEquals(LINK, text)
        assertTrue("extras: $extras", extras?.getBoolean(LEGACY_IS_SENSITIVE) == true)
    }

    private companion object {
        const val ANDROID_16 = 36
        const val BELOW_TIRAMISU = 32
        const val LEGACY_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        const val LINK = "https://journal.example/s/LLLLLLLLLLLLLLLLLLLLLL#secret"
    }
}

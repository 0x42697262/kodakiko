package dev.kodakiko.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.app.FakeAndroidKeyStore
import dev.kodakiko.app.V031Fixture
import dev.kodakiko.data.secure.LegacyRead
import dev.kodakiko.data.secure.LegacySecureStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacySecureStoreBytesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun install() {
        FakeAndroidKeyStore.install()
    }

    @After
    fun uninstall() {
        FakeAndroidKeyStore.uninstall()
    }

    @Test
    fun readBytesReturnsTheDecryptedBytesOfAnUnauthenticatedItem() {
        V031Fixture.writeLegacyItem(context, NAME, MASTER_KEY_TEXT)

        val read = LegacySecureStore(context).readBytes(NAME)

        assertTrue("expected bytes, got $read", read is LegacyRead.Bytes)
        assertArrayEquals(MASTER_KEY_TEXT.toByteArray(Charsets.US_ASCII), (read as LegacyRead.Bytes).bytes)
    }

    @Test
    fun readBytesOfAMissingItemIsMissing() {
        assertEquals(LegacyRead.Missing, LegacySecureStore(context).readBytes(NAME))
    }

    private companion object {
        const val NAME = "kodakiko.mk.${V031Fixture.HASH}"
        const val MASTER_KEY_TEXT = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}

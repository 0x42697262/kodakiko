package dev.kodakiko.data.secure

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacySecureStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val writer = ExpoSecureStoreWriter(context)

    @Before
    fun clean() = writer.reset()

    @After
    fun cleanAfter() = writer.reset()

    @Test
    fun readsItemsWrittenTheWayExpoSecureStore5802WritesThem() {
        writer.write("kodakiko.server", "https://kodakiko.example.com")
        writer.write("kodakiko.activeAccount", "{\"schemaVersion\":2,\"accountKey\":\"kodakiko.example.com/AAAAAAAAAAAAAAAAAAAAAA\",\"username\":\"alice\",\"mode\":\"server\"}")
        writer.write("kodakiko.mk.0123456789abcdef", "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA")
        writer.write("unicode", "p\u00e4ssw\u00f6rd \uD83C\uDF38")
        val store = LegacySecureStore(context)
        assertEquals(LegacyRead.Value("https://kodakiko.example.com"), store.read("kodakiko.server"))
        assertEquals(LegacyRead.Value("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"), store.read("kodakiko.mk.0123456789abcdef"))
        assertEquals(LegacyRead.Value("p\u00e4ssw\u00f6rd \uD83C\uDF38"), store.read("unicode"))
        assertTrue((store.read("kodakiko.activeAccount") as LegacyRead.Value).text.contains("\"schemaVersion\":2"))
    }

    @Test
    fun missingAuthenticatedAndBrokenItemsAreTyped() {
        writer.write("present", "x")
        writer.writeAuthenticatedPlaceholder("kodakiko.mk.0123456789abcdef")
        writer.writeRaw("notJson", "{oops")
        writer.writeRaw("hybrid", "{\"ct\":\"AA==\",\"iv\":\"AA==\",\"tlen\":128,\"scheme\":\"hybrid\",\"usesKeystoreSuffix\":true,\"keystoreAlias\":\"key_v1\",\"requireAuthentication\":false}")
        writer.writeRaw("tampered", "{\"ct\":\"AAAAAAAAAAAAAAAAAAAAAAAA\",\"iv\":\"AAAAAAAAAAAAAAAA\",\"tlen\":128,\"scheme\":\"aes\",\"usesKeystoreSuffix\":true,\"keystoreAlias\":\"key_v1\",\"requireAuthentication\":false}")
        val store = LegacySecureStore(context)
        assertEquals(LegacyRead.Missing, store.read("absent"))
        assertEquals(LegacyRead.NeedsAuthentication, store.read("kodakiko.mk.0123456789abcdef"))
        assertTrue(store.read("notJson") is LegacyRead.Unreadable)
        assertTrue(store.read("hybrid") is LegacyRead.Unreadable)
        assertTrue(store.read("tampered") is LegacyRead.Unreadable)
        assertEquals(LegacyRead.Value("x"), store.read("present"))
    }

    @Test
    fun forgetAllRemovesEveryLegacyItemAndBothKeystoreAliases() {
        writer.write("kodakiko.server", "https://kodakiko.example.com")
        writer.writeAuthenticatedPlaceholder("kodakiko.mk.0123456789abcdef")
        assertTrue(writer.aliasExists())
        LegacySecureStore(context).forgetAll()
        assertEquals(emptySet<String>(), writer.preferenceNames())
        assertFalse(writer.aliasExists())
        assertEquals(LegacyRead.Missing, LegacySecureStore(context).read("kodakiko.server"))
    }
}

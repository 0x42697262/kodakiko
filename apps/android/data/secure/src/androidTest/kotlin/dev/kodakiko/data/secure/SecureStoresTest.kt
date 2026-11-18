package dev.kodakiko.data.secure

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureStoresTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hash = "0123456789abcdef"
    private val other = "fedcba9876543210"
    private val masterKey = ByteArray(32) { (it + 1).toByte() }

    private class Gate(var answer: Boolean) : DeviceGate {
        var asked = 0

        override fun authenticate(reason: DeviceGateReason): Boolean {
            asked++
            return answer
        }
    }

    @Before
    fun clean() = SecureStores(context).wipeAll()

    @After
    fun cleanAfter() = SecureStores(context).wipeAll()

    @Test
    fun withoutASecureScreenLockTheMasterKeyIsNeverStored() {
        val gate = Gate(true)
        val vault = KeyVault(context, { DeviceLock.NONE }, gate)
        assertFalse(vault.capabilities(hash).persistent)
        assertFalse(vault.store(hash, masterKey))
        assertNull(vault.read(hash))
        assertEquals(0, gate.asked)
    }

    @Test
    fun deviceModeStoresAndReadsBehindTheDeviceGate() {
        val gate = Gate(false)
        val vault = KeyVault(context, { DeviceLock.SECURE }, gate)
        assertTrue(vault.store(hash, masterKey))
        val capabilities = vault.capabilities(hash)
        assertTrue(capabilities.persistent)
        assertEquals(KeyVaultMode.DEVICE, capabilities.mode)
        assertNull(vault.read(hash))
        assertEquals(1, gate.asked)
        gate.answer = true
        assertArrayEquals(masterKey, vault.read(hash))
        assertNull(KeyVault(context, { DeviceLock.SECURE }, gate).read(other))
        vault.forget(hash)
        assertNull(vault.read(hash))
        assertFalse(vault.capabilities(hash).persistent)
    }

    @Test
    fun aWrongLengthKeyIsRefusedAndAStoredKeyIsNeverReturnedAsAString() {
        val vault = KeyVault(context, { DeviceLock.SECURE }, Gate(true))
        assertFalse(vault.store(hash, ByteArray(31)))
        assertNull(vault.read(hash))
        val copy = masterKey.copyOf()
        assertTrue(vault.store(hash, copy))
        val read = vault.read(hash)!!
        read.fill(0)
        assertArrayEquals(masterKey, vault.read(hash))
    }

    @Test
    fun sessionsAreStoredPerAccount() {
        val sessions = SessionStore(context)
        sessions.save(hash, "kdk_s_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        sessions.save(other, "kdk_s_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
        assertEquals("kdk_s_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", sessions.load(hash))
        sessions.clear(hash)
        assertNull(sessions.load(hash))
        assertEquals("kdk_s_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", sessions.load(other))
    }

    @Test
    fun globalStoreKeepsServerAccountAndConfig() {
        val global = GlobalStore(context)
        global.setServerUrl("https://kodakiko.example.com")
        global.setActiveAccount(ActiveAccount(2, "kodakiko.example.com/AAAAAAAAAAAAAAAAAAAAAA", "alice", AccountMode.SERVER))
        global.setServerConfigJson("{\"apiVersion\":1}")
        val again = GlobalStore(context)
        assertEquals("https://kodakiko.example.com", again.serverUrl())
        assertEquals(ActiveAccount(2, "kodakiko.example.com/AAAAAAAAAAAAAAAAAAAAAA", "alice", AccountMode.SERVER), again.activeAccount())
        assertEquals("{\"apiVersion\":1}", again.serverConfigJson())
        again.clearActiveAccount()
        assertNull(GlobalStore(context).activeAccount())
        assertEquals("https://kodakiko.example.com", GlobalStore(context).serverUrl())
    }

    @Test
    fun newStoresNeverWriteIntoTheLegacyExpoFile() {
        SessionStore(context).save(hash, "kdk_s_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        GlobalStore(context).setServerUrl("https://kodakiko.example.com")
        KeyVault(context, { DeviceLock.SECURE }, Gate(true)).store(hash, masterKey)
        assertEquals(emptySet<String>(), context.getSharedPreferences("SecureStore", android.content.Context.MODE_PRIVATE).all.keys)
    }
}

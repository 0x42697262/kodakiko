package dev.kodakiko.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.AuthError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import java.security.SecureRandom

data class DevicePair(val a: E2eDevice, val b: E2eDevice)

abstract class E2eTestBase {
    protected abstract val server: E2eServer
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val devices = mutableListOf<E2eDevice>()

    protected fun e2e(block: suspend () -> Unit) =
        runTest(timeout = E2eConstants.TEST_TIMEOUT) {
            withContext(Dispatchers.IO) { block() }
        }

    @After
    fun closeDevices() {
        devices.forEach { it.close() }
        devices.clear()
    }

    protected fun newAccount(): Account {
        val bytes = ByteArray(E2eConstants.USERNAME_RANDOM_BYTES)
        SecureRandom().nextBytes(bytes)
        return Account(E2eConstants.USERNAME_PREFIX + hexOf(bytes), E2eConstants.E2E_PASSWORD)
    }

    protected fun buildDevice(name: String, account: Account): E2eDevice =
        E2eDevice(name, context, devices.size + 1, server, account).also { devices += it }

    protected suspend fun signUpDevice(name: String): E2eDevice {
        val device = buildDevice(name, newAccount())
        device.chooseServer()
        device.recoveryWords = device.auth.signUp(username = device.account.username, password = device.account.password, inviteToken = null)
        device.rememberUserId()
        return device
    }

    protected suspend fun chosenDevice(name: String, account: Account): E2eDevice = buildDevice(name, account).also { it.chooseServer() }

    protected suspend fun authFailure(block: suspend () -> Unit): AuthError {
        try {
            block()
        } catch (error: AuthError) {
            return error
        }
        throw AssertionError("expected an AuthError")
    }

    protected suspend fun setUpLocalDevice(name: String): E2eDevice {
        val device = buildDevice(name, newAccount())
        device.auth.setUpLocal(username = device.account.username, password = device.account.password)
        device.rememberUserId()
        check(device.auth.mode.value == AccountMode.LOCAL)
        return device
    }

    protected suspend fun signInDevice(name: String, account: Account): E2eDevice {
        val device = buildDevice(name, account)
        device.chooseServer()
        device.auth.signIn(username = account.username, password = account.password)
        device.rememberUserId()
        return device
    }

    protected suspend fun startPair(): DevicePair {
        val a = signUpDevice("A")
        a.syncUntilIdle()
        val b = signInDevice("B", a.account)
        b.syncUntilIdle()
        return DevicePair(a, b)
    }

    protected suspend fun E2eDevice.createSyncedEntry(input: EntryInput): CreatedEntry {
        val entry = createEntryLocally(input)
        syncUntilIdle()
        return entry
    }

    protected fun CreatedEntry.mediaIdAt(position: Int): String =
        mediaIds.getOrNull(position) ?: error("entry $entryId has no media at $position")
}

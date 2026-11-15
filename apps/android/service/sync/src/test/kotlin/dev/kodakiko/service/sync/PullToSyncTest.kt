package dev.kodakiko.service.sync

import dev.kodakiko.data.secure.AccountMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullToSyncTest {
    @Test
    fun isAvailableOnlyForServerAccountsWithSyncRunning() {
        assertTrue(pullToSyncEnabled(AccountMode.SERVER, paused = false))
        assertFalse(pullToSyncEnabled(AccountMode.LOCAL, paused = false))
        assertFalse(pullToSyncEnabled(AccountMode.SERVER, paused = true))
    }
}

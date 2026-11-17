package dev.kodakiko.service.entries

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class EntryKeyLockRaceTest : EntriesTestBase() {
    private fun EntriesHarness.whenTheTrayIsTaken(action: () -> Unit): AtomicBoolean {
        val fired = AtomicBoolean(false)
        tray.subscribe { if (fired.compareAndSet(false, true)) action() }
        return fired
    }

    private fun EntriesHarness.membershipCount(): Int = fixture.rowStore.transaction { it.memberships.all().size }

    @Test
    fun clearingTheEntryKeysWhileTheTrayIsTakenRejectsTheInsertAndWritesNoRows() = runTest {
        val harness = harness()
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES))
        val fired = harness.whenTheTrayIsTaken { harness.entryKeys.clear() }

        expectFailure<AccountLockedException> { harness.service.createFromTray(emptyInput()) }

        assertTrue(fired.get())
        assertEquals(0, harness.membershipCount())
        assertEquals(0, harness.entryCount())
        assertEquals(emptyList<String>(), harness.outboxKinds())
        assertEquals(1, harness.tray.items.size)
    }

    @Test
    fun lockingWhileTheTrayIsTakenRejectsTheInsertAndWritesNoRows() = runTest {
        val harness = harness()
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES))
        val fired = harness.whenTheTrayIsTaken { harness.lock() }

        expectFailure<AccountLockedException> { harness.service.createFromTray(emptyInput()) }

        assertTrue(fired.get())
        assertEquals(0, harness.membershipCount())
        assertEquals(0, harness.entryCount())
        assertEquals(emptyList<String>(), harness.outboxKinds())
    }
}

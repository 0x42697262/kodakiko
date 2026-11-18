package dev.kodakiko.ui.feature.lock

import dev.kodakiko.data.migration.AdoptionStatus
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.LegacyRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AdoptionGateTest {
    private val fixtureAccount = ActiveAccount(2, FIXTURE_ACCOUNT_KEY, FIXTURE_USERNAME, AccountMode.LOCAL)

    @Test
    fun aFreshInstallHasNothingPending() {
        assertNull(Fixture.emptyAdoption().pendingAccount())
    }

    @Test
    fun aV031InstallIsPendingWithItsAccountAndOnlyTheAccountItemIsRead() {
        Fixture.install()
        val legacy = Fixture.legacy(ByteArray(32) { 7 })
        assertEquals(fixtureAccount, Fixture.adoption(legacy).pendingAccount())
        assertEquals(listOf("kodakiko.activeAccount"), legacy.reads)
        assertEquals(0, legacy.forgotten)
    }

    @Test
    fun anUnreadableAccountItemIsNotPending() {
        val legacy = FakeLegacy(mutableMapOf("kodakiko.activeAccount" to LegacyRead.Unreadable("bad tag")))
        assertNull(Fixture.adoption(legacy).pendingAccount())
        val notJson = FakeLegacy(mutableMapOf("kodakiko.activeAccount" to LegacyRead.Value("{not json")))
        assertNull(Fixture.adoption(notJson).pendingAccount())
    }

    @Test
    fun nothingIsPendingOnceAdopted() {
        Fixture.install()
        val legacy = Fixture.legacy()
        val adoption = Fixture.adoption(legacy, FakeTarget())
        assertEquals(AdoptionStatus.ADOPTED, adoption.run().status)
        assertNull(adoption.pendingAccount())
    }

    @Test
    fun aCorruptJournalIsReportedAndNothingIsWrittenOrDeleted() {
        Fixture.corrupt()
        val before = Fixture.sha256(Fixture.databaseFile)
        val objects = File(context.filesDir, "kodakiko/$FIXTURE_HASH").walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        val legacy = Fixture.legacy(ByteArray(32) { 7 })
        val target = FakeTarget()
        val result = Fixture.adoption(legacy, target).run()
        assertEquals(AdoptionStatus.CORRUPT_DATABASE, result.status)
        assertEquals(FIXTURE_HASH, result.accountHash)
        assertFalse(result.masterKeyCarried)
        assertFalse(result.needsPassword)
        assertEquals(0, target.calls)
        assertEquals(0, legacy.forgotten)
        assertTrue(Fixture.databaseFile.isFile)
        assertEquals(before, Fixture.sha256(Fixture.databaseFile))
        assertEquals(objects, File(context.filesDir, "kodakiko/$FIXTURE_HASH").walkTopDown().filter { it.isFile }.map { it.name }.toSet())
        assertTrue(legacy.reads.none { it.startsWith("kodakiko.mk.") })
    }

    @Test
    fun aCorruptJournalStaysPendingAndIsReportedAgain() {
        Fixture.corrupt()
        val before = Fixture.sha256(Fixture.databaseFile)
        val legacy = Fixture.legacy()
        val adoption = Fixture.adoption(legacy)
        assertEquals(AdoptionStatus.CORRUPT_DATABASE, adoption.run().status)
        assertEquals(fixtureAccount, adoption.pendingAccount())
        assertEquals(AdoptionStatus.CORRUPT_DATABASE, adoption.run().status)
        assertEquals(before, Fixture.sha256(Fixture.databaseFile))
    }

    @Test
    fun anIntactFixtureStillAdoptsAndAsksForThePasswordWithoutAMasterKey() {
        Fixture.install()
        val target = FakeTarget()
        val result = Fixture.adoption(Fixture.legacy(), target).run()
        assertEquals(AdoptionStatus.ADOPTED, result.status)
        assertTrue(result.needsPassword)
        assertEquals(fixtureAccount, target.account)
    }
}

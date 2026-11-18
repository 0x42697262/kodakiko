package dev.kodakiko.app.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.app.FakeKodakikoServer
import dev.kodakiko.app.FakeKodakikoServer.Companion.GET
import dev.kodakiko.app.ServerFixture
import dev.kodakiko.app.ServerV031App
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.contract.Routes
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import dev.kodakiko.app.TestTags as AppTestTags

@RunWith(RobolectricTestRunner::class)
class MoreWiringTest : AppWiringHarness() {
    private val rows: List<Pair<String, String>> = listOf(
        TestTags.More.TAGS to TestTags.Tags.SCREEN,
        TestTags.More.STATISTICS to TestTags.Stats.SCREEN,
        TestTags.More.TRASH to TestTags.Trash.SCREEN,
        TestTags.More.DATA_STORAGE to TestTags.Settings.STORAGE_SCREEN,
        TestTags.More.SETTINGS to TestTags.Settings.SCREEN,
        TestTags.More.ABOUT to TestTags.Settings.ABOUT_SCREEN,
    )

    private fun pulls(): Int = ServerFixture.requireServer().count(GET, Routes.syncState())

    private fun state(value: ToggleableState): SemanticsMatcher {
        val matcher = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, value)
        return matcher or hasAnyDescendant(matcher)
    }

    private fun openMore() {
        unlockToGallery()
        tap(TestTags.Tabs.MORE)
        awaitTag(TestTags.More.SCREEN)
    }

    private fun syncNowFromOutsideTheUi() {
        val done = AtomicBoolean(false)
        thread {
            try {
                runBlocking { app.graph.syncService.syncNow() }
            } finally {
                done.set(true)
            }
        }
        compose.waitUntil(SYNC_WAIT_MS) { done.get() }
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theMoreTabReplacesThePlaceholderAndEachRowOpensTheRealScreen() {
        openMore()
        assertFalse(exists(AppTestTags.APP_PLACEHOLDER))
        assertFalse(exists(TestTags.More.PAUSE_SYNC))
        assertTrue(exists(TestTags.More.HIDE_SHARED))
        for ((row, screen) in rows) {
            click(row)
            awaitTag(screen)
            back()
            awaitGone(screen)
            awaitTag(TestTags.More.SCREEN)
        }
        assertEquals(Route.More, app.graph.navigator.currentTab)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun statisticsCountTheJournalOnThisDevice() {
        openMore()
        click(TestTags.More.STATISTICS)
        awaitTag(TestTags.Stats.OVERVIEW)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasTestTag(TestTags.Stats.OVERVIEW) and hasAnyDescendant(hasText("1"))).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(exists(TestTags.Stats.DEVICE_CARD))
        assertFalse(exists(TestTags.Stats.SERVER_CARD))
    }

    @Test
    @Config(application = ServerV031App::class)
    fun statisticsOfAServerAccountShowTheServerUsageOfTheSignedInUser() {
        openMore()
        click(TestTags.More.STATISTICS)
        awaitTag(TestTags.Stats.SERVER_CARD, SYNC_WAIT_MS)
        val quota = FakeKodakikoServer.config(true).defaultQuotaBytes
        compose.onNodeWithTag(TestTags.Stats.SERVER_CARD)
            .performScrollTo()
            .assert(hasAnyDescendant(hasText(formatBytes(FakeKodakikoServer.ME_USED_BYTES))))
            .assert(hasAnyDescendant(hasText(formatBytes(quota))))
            .assert(hasAnyDescendant(hasText(formatBytes(quota - FakeKodakikoServer.ME_USED_BYTES))))
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasTestTag(TestTags.Stats.OVERVIEW) and hasAnyDescendant(hasText("1"))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun lockNowInMoreLocksTheJournal() {
        openMore()
        click(TestTags.More.LOCK_NOW)
        awaitTag(TestTags.Lock.SCREEN, UNLOCK_WAIT_MS)
        assertEquals(AccountState.Locked, app.graph.accountState.value)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun helpOpensTheV031HelpPageInTheBrowser() {
        openMore()
        while (shadowOf(app).nextStartedActivity != null) Unit
        click(TestTags.More.HELP)
        var started: Intent? = null
        compose.waitUntil(WAIT_MS) {
            started = shadowOf(app).nextStartedActivity
            started != null
        }
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(Uri.parse(app.getString(R.string.more_help_url)), started!!.data)
    }

    @Test
    @Config(application = ServerV031App::class)
    fun hideSharedInMoreIsTheGallerySettingAndTheSharedTabStaysLikeV031() {
        openMore()
        awaitTag(TestTags.Tabs.SHARED)
        compose.onNodeWithTag(TestTags.More.HIDE_SHARED).assert(state(ToggleableState.Off))
        click(TestTags.More.HIDE_SHARED)
        compose.onNodeWithTag(TestTags.More.HIDE_SHARED).assert(state(ToggleableState.On))
        push(Route.SettingsGallery)
        awaitTag(TestTags.Settings.HIDE_SHARED)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasTestTag(TestTags.Settings.HIDE_SHARED) and state(ToggleableState.On)).fetchSemanticsNodes().isNotEmpty()
        }
        back()
        awaitTag(TestTags.More.SCREEN)
        assertTrue(exists(TestTags.Tabs.SHARED))
    }

    @Test
    @Config(application = ServerV031App::class)
    fun pauseSyncStopsSyncPassesUntilItIsTurnedOffWhichSyncsAtOnce() {
        unlockToGallery()
        compose.waitUntil(SYNC_WAIT_MS) { pulls() >= 1 && !app.graph.syncService.status().running }
        tap(TestTags.Tabs.MORE)
        awaitTag(TestTags.More.PAUSE_SYNC)
        compose.onNodeWithTag(TestTags.More.PAUSE_SYNC).assert(state(ToggleableState.Off))
        click(TestTags.More.PAUSE_SYNC)
        compose.onNodeWithTag(TestTags.More.PAUSE_SYNC).assert(state(ToggleableState.On))
        val paused = pulls()
        syncNowFromOutsideTheUi()
        syncNowFromOutsideTheUi()
        assertEquals(paused, pulls())
        click(TestTags.More.PAUSE_SYNC)
        compose.onNodeWithTag(TestTags.More.PAUSE_SYNC).assert(state(ToggleableState.Off))
        compose.waitUntil(SYNC_WAIT_MS) { pulls() > paused }
    }
}

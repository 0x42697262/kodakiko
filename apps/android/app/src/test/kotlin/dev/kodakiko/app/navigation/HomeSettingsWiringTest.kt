package dev.kodakiko.app.navigation

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import dev.kodakiko.app.CalendarStartServerV031App
import dev.kodakiko.app.CalendarStartV031App
import dev.kodakiko.app.FakeKodakikoServer
import dev.kodakiko.app.ServerV031App
import dev.kodakiko.app.ServerV031NoSharingApp
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class HomeSettingsTest {
    private val defaults = DeviceSettingsDefaults.ANDROID

    @Test
    fun aServerWithSharingShowsTheSharedTab() {
        assertEquals(
            HomeSettings(hideBarsOnScroll = true, sharedTabVisible = true),
            HomeSettings.of(defaults, AccountMode.SERVER, FakeKodakikoServer.config(sharingEnabled = true)),
        )
    }

    @Test
    fun theSharedTabIsHiddenWhenTheServerTurnedSharingOffOrIsUnknownOrTheJournalIsLocal() {
        assertFalse(HomeSettings.of(defaults, AccountMode.SERVER, FakeKodakikoServer.config(sharingEnabled = false)).sharedTabVisible)
        assertFalse(HomeSettings.of(defaults, AccountMode.SERVER, null).sharedTabVisible)
        assertFalse(HomeSettings.of(defaults, AccountMode.LOCAL, FakeKodakikoServer.config(sharingEnabled = true)).sharedTabVisible)
        assertFalse(HomeSettings.of(defaults, AccountMode.LOCAL, null).sharedTabVisible)
    }

    @Test
    fun hideBarsOnScrollComesFromTheDeviceSettings() {
        val config = FakeKodakikoServer.config(sharingEnabled = true)
        assertFalse(HomeSettings.of(defaults.copy(hideBarsOnScroll = false), AccountMode.SERVER, config).hideBarsOnScroll)
        assertTrue(HomeSettings.of(defaults.copy(hideBarsOnScroll = true), AccountMode.LOCAL, null).hideBarsOnScroll)
    }
}

@RunWith(RobolectricTestRunner::class)
class HomeSettingsWiringTest : AppWiringHarness() {
    private fun assertTabs(selected: String, shared: Boolean) {
        compose.onNodeWithTag(selected).assertIsSelected()
        for (tag in listOf(TestTags.Tabs.GALLERY, TestTags.Tabs.CALENDAR, TestTags.Tabs.MORE)) {
            assertTrue(tag, exists(tag))
            if (tag != selected) compose.onNodeWithTag(tag).assertIsNotSelected()
        }
        assertEquals(shared, exists(TestTags.Tabs.SHARED))
    }

    private fun unlockToCalendar() {
        launch()
        enterPassword()
        awaitTag(TestTags.Calendar.SCREEN, UNLOCK_WAIT_MS)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun aLocalJournalHasNoSharedTabAndStartsOnTheGalleryWithBarsHidingOnScroll() {
        unlockToGallery()
        assertTabs(TestTags.Tabs.GALLERY, shared = false)
        assertEquals(Route.Gallery, app.graph.navigator.currentTab)
        compose.waitUntil(WAIT_MS) { app.graph.homeSettings.value == HomeSettings(hideBarsOnScroll = true, sharedTabVisible = false) }
    }

    @Test
    @Config(application = CalendarStartV031App::class)
    fun theStoredStartScreenAndHideBarsSettingsAreUsedAfterUnlock() {
        unlockToCalendar()
        assertEquals(Route.Calendar, app.graph.navigator.currentTab)
        assertTabs(TestTags.Tabs.CALENDAR, shared = false)
        assertFalse(exists(listItem(TestTags.Gallery.TILE, 0)))
        compose.waitUntil(WAIT_MS) { app.graph.homeSettings.value == HomeSettings(hideBarsOnScroll = false, sharedTabVisible = false) }
    }

    @Test
    @Config(application = CalendarStartV031App::class)
    fun backFromTheGalleryReturnsToTheStoredStartTab() {
        unlockToCalendar()
        tap(TestTags.Tabs.GALLERY)
        awaitTag(listItem(TestTags.Gallery.TILE, 0))
        back()
        awaitTag(TestTags.Calendar.SCREEN)
        assertEquals(Route.Calendar, app.graph.navigator.currentTab)
    }

    @Test
    @Config(application = ServerV031App::class)
    fun aServerWithSharingShowsTheSharedTab() {
        unlockToGallery()
        awaitTag(TestTags.Tabs.SHARED)
        assertTabs(TestTags.Tabs.GALLERY, shared = true)
        compose.waitUntil(WAIT_MS) { app.graph.homeSettings.value == HomeSettings(hideBarsOnScroll = true, sharedTabVisible = true) }
        tap(TestTags.Tabs.SHARED)
        awaitTag(TestTags.Shared.SCREEN)
        assertEquals(Route.Shared, app.graph.navigator.currentTab)
    }

    @Test
    @Config(application = ServerV031NoSharingApp::class)
    fun aServerWithSharingTurnedOffHidesTheSharedTab() {
        unlockToGallery()
        compose.waitUntil(WAIT_MS) { app.graph.homeSettings.value == HomeSettings(hideBarsOnScroll = true, sharedTabVisible = false) }
        assertTabs(TestTags.Tabs.GALLERY, shared = false)
    }

    @Test
    @Config(application = CalendarStartServerV031App::class)
    fun aServerAccountUsesItsStoredStartScreenToo() {
        unlockToCalendar()
        awaitTag(TestTags.Tabs.SHARED)
        assertTabs(TestTags.Tabs.CALENDAR, shared = true)
        compose.waitUntil(WAIT_MS) { app.graph.homeSettings.value == HomeSettings(hideBarsOnScroll = false, sharedTabVisible = true) }
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun changingHideBarsAndTheStartScreenInAppearanceTakesEffect() {
        unlockToGallery()
        push(Route.Settings)
        click(TestTags.Settings.ROW_APPEARANCE)
        awaitTag(TestTags.Settings.APPEARANCE_SCREEN)
        click(TestTags.Settings.HIDE_BARS_ON_SCROLL)
        compose.waitUntil(WAIT_MS) { !app.graph.homeSettings.value.hideBarsOnScroll }
        click(TestTags.Settings.START_SCREEN)
        tap(TestTags.Settings.START_SCREEN + ".calendar")
        awaitGone(TestTags.Settings.START_SCREEN + ".dialog")
        tap(TestTags.Settings.BACK)
        awaitTag(TestTags.Settings.SCREEN)
        assertEquals(Route.Gallery, app.graph.navigator.currentTab)
        click(TestTags.Settings.ROW_LOCK_NOW)
        enterPassword()
        awaitTag(TestTags.Settings.SCREEN, UNLOCK_WAIT_MS)
        assertEquals(Route.Calendar, app.graph.navigator.currentTab)
        compose.waitUntil(WAIT_MS) { app.graph.homeSettings.value == HomeSettings(hideBarsOnScroll = false, sharedTabVisible = false) }
        back()
        awaitTag(TestTags.Calendar.SCREEN)
    }
}

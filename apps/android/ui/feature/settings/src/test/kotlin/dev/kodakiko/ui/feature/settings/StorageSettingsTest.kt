@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.data.media.CacheUsage
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val CACHE_CAPS = listOf(536_870_912L, 1_073_741_824L, 2_147_483_648L, 5_368_709_120L, 10_737_418_240L, 0L)
private val LARGE_THRESHOLDS = listOf(52_428_800L, 104_857_600L, 262_144_000L, 524_288_000L, 1_073_741_824L)

@RunWith(RobolectricTestRunner::class)
class StorageSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private class Opened(val vm: StorageSettingsViewModel, val events: MutableList<SettingsEvent>) {
        val state: StorageSettingsState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = StorageSettingsViewModel(h.deps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    @Test
    fun openingTheScreenReadsTheCacheUsageAndTheDeviceStorage() = runTest {
        val o = open()
        assertEquals(h.storage.usage, o.state.usage)
        assertEquals(h.storage.device, o.state.device)
        assertEquals(defaults, o.state.settings)
        assertFalse(o.state.local)
        assertFalse(o.state.confirmingClear)
        assertTrue(FakeSettingsStorage.USAGE in h.storage.calls)
        assertTrue(FakeSettingsStorage.DEVICE in h.storage.calls)
    }

    @Test
    fun unknownDeviceStorageLeavesTheBarOut() = runTest {
        h.storage.device = null
        assertNull(open().state.device)
    }

    @Test
    fun aNewCacheCapIsSavedThenEnforcedAndTheUsageIsReadAgain() = runTest {
        val o = open()
        h.storage.calls.clear()
        h.storage.usage = h.storage.usage.copy(originalsBytes = 100 * MB)
        o.vm.onAction(StorageSettingsAction.SetCacheCap(536_870_912L))
        runCurrent()
        assertEquals(536_870_912L, h.store.value.originalCacheCapBytes)
        assertEquals(listOf(536_870_912L), h.storage.capsAtEnforce)
        assertEquals(FakeSettingsStorage.ENFORCE_CAP, h.storage.calls.first())
        assertTrue(FakeSettingsStorage.USAGE in h.storage.calls.drop(1))
        assertEquals(100 * MB, o.state.usage!!.originalsBytes)
        assertEquals(defaults.copy(originalCacheCapBytes = 536_870_912L), h.store.value)
    }

    @Test
    fun noLimitIsACapOfZero() = runTest {
        val o = open()
        o.vm.onAction(StorageSettingsAction.SetCacheCap(0L))
        runCurrent()
        assertEquals(0L, h.store.value.originalCacheCapBytes)
        assertEquals(listOf(0L), h.storage.capsAtEnforce)
    }

    @Test
    fun clearingDownloadedOriginalsAsksFirstAndThenReadsTheUsageAgain() = runTest {
        val o = open()
        o.vm.onAction(StorageSettingsAction.Clear)
        runCurrent()
        assertTrue(o.state.confirmingClear)
        assertFalse(FakeSettingsStorage.CLEAR_ORIGINALS in h.storage.calls)
        o.vm.onAction(StorageSettingsAction.ConfirmClear)
        runCurrent()
        assertFalse(o.state.confirmingClear)
        val cleared = h.storage.calls.indexOf(FakeSettingsStorage.CLEAR_ORIGINALS)
        assertTrue(cleared >= 0)
        assertTrue(FakeSettingsStorage.USAGE in h.storage.calls.drop(cleared + 1))
        assertEquals(0L, o.state.usage!!.originalsBytes)
    }

    @Test
    fun dismissingTheClearQuestionKeepsTheOriginals() = runTest {
        val o = open()
        o.vm.onAction(StorageSettingsAction.Clear)
        o.vm.onAction(StorageSettingsAction.DismissClear)
        runCurrent()
        assertFalse(o.state.confirmingClear)
        assertFalse(FakeSettingsStorage.CLEAR_ORIGINALS in h.storage.calls)
    }

    @Test
    fun theBackupRowsOpenCreateBackupAndRestoreBackup() = runTest {
        val o = open()
        o.vm.onAction(StorageSettingsAction.Export)
        o.vm.onAction(StorageSettingsAction.Import)
        o.vm.onAction(StorageSettingsAction.Back)
        runCurrent()
        assertEquals(listOf(SettingsEvent.Open(Route.SettingsExport), SettingsEvent.Open(Route.SettingsImport), SettingsEvent.Back), o.events)
    }

    @Test
    fun aLocalJournalIsMarkedLocal() = runTest {
        h.account.modeState.value = AccountMode.LOCAL
        assertTrue(open().state.local)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h2400dp-mdpi")
class StorageSettingsScreenTest : PreferenceScreenHarness() {
    private val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsStorage))

    private fun open() {
        compose.showWith(navigator) { StorageSettingsScreen(viewModel = StorageSettingsViewModel(h.deps)) }
        await(TestTags.Settings.STORAGE_SCREEN)
        await(TestTags.Settings.USAGE_ORIGINALS)
    }

    @Test
    fun theUsageSectionShowsTheDeviceSpaceAndTheFourCacheFigures() {
        open()
        assertTrue(compose.showsText(text(R.string.storage_usage)))
        assertTrue(compose.showsText(text(R.string.prefs_data_storage)))
        compose.onNodeWithTag(TestTags.Storage.DEVICE, useUnmergedTree = true).assertTextEquals(text(R.string.storage_available, formatBytes(20 * GB), formatBytes(64 * GB)))
        val figures = mapOf(
            TestTags.Settings.USAGE_ORIGINALS to (R.string.settings_usage_originals to 300 * MB),
            TestTags.Settings.USAGE_THUMBNAILS to (R.string.settings_usage_thumbnails to 12 * MB),
            TestTags.Settings.USAGE_PINNED to (R.string.settings_usage_pinned to 40 * MB),
            TestTags.Settings.USAGE_PENDING to (R.string.settings_usage_pending to 5 * MB),
        )
        for ((tag, figure) in figures) {
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertTextEquals(formatBytes(figure.second))
            assertTrue(compose.showsText(text(figure.first)))
        }
    }

    @Test
    fun withoutDeviceFiguresThereIsNoDeviceLine() {
        h.storage.device = null
        open()
        assertFalse(compose.exists(TestTags.Storage.DEVICE))
    }

    @Test
    fun theRowsFollowTheV031OrderOnAServerAccount() {
        open()
        assertInOrder(
            listOf(
                TestTags.Storage.LOCATION,
                TestTags.Settings.ROW_EXPORT,
                TestTags.Settings.ROW_IMPORT,
                TestTags.Settings.AUTOPLAY,
                TestTags.Settings.CACHE_CAP,
                TestTags.Settings.CLEAR_ORIGINALS,
                TestTags.Settings.WIFI_ONLY,
                TestTags.Settings.AUTOPLAY_MOBILE,
                TestTags.Settings.LARGE_THRESHOLD,
            ),
        )
        assertTrue(compose.topOf(TestTags.Settings.USAGE_PENDING) < compose.topOf(TestTags.Storage.LOCATION))
        assertTrue(compose.showsText(text(R.string.storage_backup)))
        assertTrue(compose.showsText(text(R.string.storage_cache)))
        compose.onNodeWithTag(TestTags.Storage.LOCATION).assert(contains(text(R.string.storage_location) + ": " + text(R.string.storage_location_android)))
        compose.onNodeWithTag(TestTags.Settings.ROW_EXPORT).assert(contains(text(R.string.storage_create_backup))).assert(contains(text(R.string.prefs_export_summary)))
        compose.onNodeWithTag(TestTags.Settings.ROW_IMPORT).assert(contains(text(R.string.storage_restore_backup))).assert(contains(text(R.string.storage_restore_summary)))
        compose.onNodeWithTag(TestTags.Settings.AUTOPLAY).assert(contains(text(R.string.prefs_autoplay_summary)))
        compose.onNodeWithTag(TestTags.Settings.CACHE_CAP).assert(contains(text(R.string.settings_cache_cap))).assert(contains(formatBytes(2_147_483_648L)))
        compose.onNodeWithTag(TestTags.Settings.CLEAR_ORIGINALS).assert(contains(text(R.string.storage_used, formatBytes(300 * MB))))
        compose.onNodeWithTag(TestTags.Settings.WIFI_ONLY).assert(contains(text(R.string.settings_wifi_only)))
        compose.onNodeWithTag(TestTags.Settings.AUTOPLAY_MOBILE).assert(contains(text(R.string.prefs_autoplay_mobile_summary)))
        compose.onNodeWithTag(TestTags.Settings.LARGE_THRESHOLD).assert(contains(text(R.string.settings_large_means))).assert(contains(formatBytes(104_857_600L)))
    }

    @Test
    fun aLocalJournalKeepsOnlyTheBackupRowsAndAutoplay() {
        h.account.modeState.value = AccountMode.LOCAL
        open()
        for (tag in listOf(TestTags.Storage.LOCATION, TestTags.Settings.ROW_EXPORT, TestTags.Settings.ROW_IMPORT, TestTags.Settings.AUTOPLAY)) assertTrue(tag, compose.exists(tag))
        for (tag in listOf(TestTags.Settings.CACHE_CAP, TestTags.Settings.CLEAR_ORIGINALS, TestTags.Settings.WIFI_ONLY, TestTags.Settings.AUTOPLAY_MOBILE, TestTags.Settings.LARGE_THRESHOLD)) {
            assertFalse(tag, compose.exists(tag))
        }
        assertTrue(compose.exists(TestTags.Settings.USAGE_ORIGINALS))
    }

    @Test
    fun autoplayAndTheWifiRuleWriteTheirFields() {
        open()
        toggles(TestTags.Settings.AUTOPLAY) { it.autoplayVideos }
        toggles(TestTags.Settings.WIFI_ONLY) { it.largeUploadsOnWifiOnly }
        toggles(TestTags.Settings.AUTOPLAY_MOBILE) { it.autoplayOnMobileData }
        assertEquals(defaults, h.store.value)
    }

    @Test
    fun autoplayOnMobileDataIsDisabledWhileAutoplayIsOff() {
        open()
        compose.onNodeWithTag(TestTags.Settings.AUTOPLAY_MOBILE).performScrollTo().assertIsEnabled()
        click(TestTags.Settings.AUTOPLAY)
        assertOnlyChanged(defaults, defaults.copy(autoplayVideos = false))
        compose.onNodeWithTag(TestTags.Settings.AUTOPLAY_MOBILE).performScrollTo().assertIsNotEnabled()
        click(TestTags.Settings.AUTOPLAY_MOBILE)
        assertFalse(h.store.value.autoplayOnMobileData)
    }

    @Test
    fun theCacheCapOffersTheV031SizesAndNoLimitAndEnforcesTheChoice() {
        open()
        click(TestTags.Settings.CACHE_CAP)
        for (cap in CACHE_CAPS) {
            val label = if (cap == 0L) text(R.string.settings_no_limit) else formatBytes(cap)
            compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.CACHE_CAP, cap)).performScrollTo().assert(labeled(label))
        }
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.CACHE_CAP, 2_147_483_648L)).assert(selected())
        tap(SettingsTestTags.option(TestTags.Settings.CACHE_CAP, 0L))
        assertOnlyChanged(defaults, defaults.copy(originalCacheCapBytes = 0L))
        compose.waitUntil(WAIT_MS) { h.storage.capsAtEnforce.isNotEmpty() }
        assertEquals(listOf(0L), h.storage.capsAtEnforce)
        compose.onNodeWithTag(TestTags.Settings.CACHE_CAP).performScrollTo().assert(contains(text(R.string.settings_no_limit)))
    }

    @Test
    fun largeMeansOffersTheV031Thresholds() {
        open()
        click(TestTags.Settings.LARGE_THRESHOLD)
        for (threshold in LARGE_THRESHOLDS) {
            compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.LARGE_THRESHOLD, threshold)).performScrollTo().assert(labeled(formatBytes(threshold)))
        }
        tap(SettingsTestTags.option(TestTags.Settings.LARGE_THRESHOLD, 524_288_000L))
        assertOnlyChanged(defaults, defaults.copy(largeUploadThresholdBytes = 524_288_000L))
    }

    @Test
    fun clearingOriginalsAsksAndThenShowsTheNewUsage() {
        open()
        click(TestTags.Settings.CLEAR_ORIGINALS)
        await(TestTags.Settings.CLEAR_SHEET)
        assertTrue(compose.showsText(text(R.string.settings_clear_originals_confirm)))
        compose.onNodeWithTag(TestTags.Settings.CLEAR_CONFIRM).assert(labeled(text(R.string.settings_clear_action)))
        tap(TestTags.Settings.CLEAR_CONFIRM)
        compose.waitUntil(WAIT_MS) { !compose.exists(TestTags.Settings.CLEAR_SHEET) }
        compose.waitUntil(WAIT_MS) { FakeSettingsStorage.CLEAR_ORIGINALS in h.storage.calls }
        compose.waitUntil(WAIT_MS) { textOf(TestTags.Settings.USAGE_ORIGINALS) == formatBytes(0) }
        compose.onNodeWithTag(TestTags.Settings.CLEAR_ORIGINALS).performScrollTo().assert(contains(text(R.string.storage_used, formatBytes(0))))
    }

    @Test
    fun theBackupRowsOpenTheirScreens() {
        open()
        click(TestTags.Settings.ROW_EXPORT)
        click(TestTags.Settings.ROW_IMPORT)
        assertEquals(listOf(Route.SettingsExport, Route.SettingsImport), navigator.pushed)
    }

    @Test
    fun theUsageCanBeEmpty() {
        h.storage.usage = CacheUsage(0, 0, 0, 0)
        open()
        compose.onNodeWithTag(TestTags.Settings.USAGE_PINNED, useUnmergedTree = true).assertTextEquals(formatBytes(0))
    }

    private fun textOf(tag: String): String? =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
}

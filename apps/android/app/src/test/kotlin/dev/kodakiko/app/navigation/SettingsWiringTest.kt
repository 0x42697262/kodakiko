package dev.kodakiko.app.navigation

import android.content.Intent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.app.ServerV031App
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.V031Media
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.settings.PreferenceHighlighted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SettingsWiringTest : AppWiringHarness() {
    private val screens: List<Pair<String, String>> = listOf(
        TestTags.Settings.ROW_APPEARANCE to TestTags.Settings.APPEARANCE_SCREEN,
        TestTags.Settings.ROW_GALLERY to TestTags.Settings.GALLERY_SCREEN,
        TestTags.Settings.ROW_TAGS to TestTags.Tags.SCREEN,
        TestTags.Settings.ROW_CAPTURE to TestTags.Settings.CAPTURE_SCREEN,
        TestTags.Settings.ROW_SYNC to TestTags.Settings.SYNC_SCREEN,
        TestTags.Settings.ROW_STORAGE to TestTags.Settings.STORAGE_SCREEN,
        TestTags.Settings.ROW_SECURITY to TestTags.Settings.SECURITY_SCREEN,
    )

    private fun openSettings() {
        unlockToGallery()
        push(Route.Settings)
        awaitTag(TestTags.Settings.SCREEN)
    }

    private fun startedIntent(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    @Config(application = UpdatedV031App::class)
    fun theSettingsRootOfALocalJournalShowsItsRowsAndEachRowOpensTheRealScreen() {
        openSettings()
        for (tag in listOf(TestTags.Settings.ROW_LINKS, TestTags.Settings.ROW_ADMIN, TestTags.Settings.ROW_SIGN_OUT)) assertFalse(tag, exists(tag))
        for ((row, screen) in screens) {
            click(row)
            awaitTag(screen)
            back()
            awaitGone(screen)
            awaitTag(TestTags.Settings.SCREEN)
        }
        assertEquals(listOf(Route.Gallery, Route.Settings), app.graph.navigator.routes)
    }

    @Test
    @Config(application = ServerV031App::class)
    fun aServerAccountWithPublicLinksAlsoGetsLinksAndSignOut() {
        openSettings()
        awaitTag(TestTags.Settings.ROW_LINKS)
        assertTrue(exists(TestTags.Settings.ROW_SIGN_OUT))
        assertFalse(exists(TestTags.Settings.ROW_ADMIN))
        click(TestTags.Settings.ROW_LINKS)
        awaitTag(TestTags.Links.LIST_SCREEN)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun searchingAutoLockOpensSecurityAndPrivacyWithTheRowHighlighted() {
        openSettings()
        tap(TestTags.Settings.SEARCH)
        awaitTag(TestTags.Settings.SEARCH_FIELD)
        compose.onNodeWithTag(TestTags.Settings.SEARCH_FIELD).performTextInput("auto-lock")
        awaitTag(listItem(TestTags.Settings.RESULT, 0))
        assertFalse(exists(listItem(TestTags.Settings.RESULT, 1)))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(listItem(TestTags.Settings.RESULT, 0)).performClick()
        compose.mainClock.advanceTimeBy(HIGHLIGHT_VISIBLE_AT_MS)
        assertTrue(exists(TestTags.Settings.SECURITY_SCREEN))
        compose.onNodeWithTag(TestTags.Settings.AUTO_LOCK).assert(SemanticsMatcher.expectValue(PreferenceHighlighted, true))
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf(Route.Gallery, Route.Settings, Route.SettingsSecurity), app.graph.navigator.routes)
        back()
        awaitTag(TestTags.Settings.SCREEN)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun dataAndStorageShowsTheRealCacheUsageOfTheJournal() {
        openSettings()
        click(TestTags.Settings.ROW_STORAGE)
        awaitTag(TestTags.Settings.USAGE_ORIGINALS)
        compose.onNodeWithTag(TestTags.Settings.USAGE_ORIGINALS, useUnmergedTree = true).assertTextEquals(formatBytes(ORIGINAL_BYTES))
        compose.onNodeWithTag(TestTags.Settings.USAGE_THUMBNAILS, useUnmergedTree = true).assertTextEquals(formatBytes(THUMBNAIL_BYTES))
        compose.onNodeWithTag(TestTags.Settings.USAGE_PINNED, useUnmergedTree = true).assertTextEquals(formatBytes(0))
        compose.onNodeWithTag(TestTags.Settings.USAGE_PENDING, useUnmergedTree = true).assertTextEquals(formatBytes(ORIGINAL_BYTES + THUMBNAIL_BYTES))
        assertFalse(exists(TestTags.Settings.CACHE_CAP))
        assertTrue(exists(TestTags.Settings.ROW_EXPORT))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun createBackupAsksForAFolderAndRestoreBackupForFilesThroughTheSystemPicker() {
        openSettings()
        click(TestTags.Settings.ROW_STORAGE)
        awaitTag(TestTags.Settings.USAGE_ORIGINALS)
        click(TestTags.Settings.ROW_EXPORT)
        awaitTag(TestTags.Settings.EXPORT_SCREEN)
        while (startedIntent() != null) Unit
        click(TestTags.Export.START)
        var folder: Intent? = null
        compose.waitUntil(WAIT_MS) {
            folder = startedIntent()
            folder != null
        }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, folder!!.action)
        tap(TestTags.Settings.BACK)
        click(TestTags.Settings.ROW_IMPORT)
        awaitTag(TestTags.Import.SCREEN)
        click(TestTags.Import.START)
        var files: Intent? = null
        compose.waitUntil(WAIT_MS) {
            files = startedIntent()
            files != null
        }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, files!!.action)
        assertTrue(files!!.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertNotNull(files!!.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun encryptingThumbnailsInSecurityRebuildsThemWithoutThePlainCopy() {
        assertTrue(V031Media.plainThumbnail(app).exists())
        openSettings()
        click(TestTags.Settings.ROW_SECURITY)
        click(TestTags.Settings.ENCRYPT_THUMBNAILS)
        tap(TestTags.Settings.THUMBNAIL_CONFIRM)
        compose.waitUntil(WAIT_MS) { !V031Media.plainThumbnail(app).exists() }
        awaitGone(TestTags.Settings.THUMBNAIL_PROGRESS)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun lockNowInTheSettingsLocksTheJournal() {
        openSettings()
        click(TestTags.Settings.ROW_LOCK_NOW)
        awaitTag(TestTags.Lock.SCREEN, UNLOCK_WAIT_MS)
        assertFalse(exists(TestTags.Settings.SCREEN))
    }

    private companion object {
        const val ORIGINAL_BYTES = 24_500L
        const val THUMBNAIL_BYTES = 2_532L
        const val HIGHLIGHT_VISIBLE_AT_MS = 1_200L
    }
}

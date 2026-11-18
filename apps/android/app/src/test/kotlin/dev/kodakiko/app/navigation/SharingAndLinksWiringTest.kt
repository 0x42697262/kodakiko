package dev.kodakiko.app.navigation

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.app.FakeAndroidKeyStore
import dev.kodakiko.app.KodakikoApp
import dev.kodakiko.app.MainActivity
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.V031Fixture
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GalleryView
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SharingAndLinksWiringTest {
    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private val app: KodakikoApp get() = ApplicationProvider.getApplicationContext()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun forgetFileProviderRootsOfEarlierTests() {
        val cache = FileProvider::class.java.getDeclaredField(FILE_PROVIDER_CACHE).apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        scenario?.close()
        FakeAndroidKeyStore.uninstall()
    }

    private fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun awaitTag(tag: String, timeoutMs: Long = WAIT_MS) {
        compose.waitUntil(timeoutMs) { exists(tag) }
    }

    private fun awaitGone(tag: String, timeoutMs: Long = WAIT_MS) {
        compose.waitUntil(timeoutMs) { !exists(tag) }
    }

    private fun click(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun back() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun push(route: Route) {
        scenario!!.onActivity { app.graph.navigator.push(route) }
        compose.waitForIdle()
    }

    private fun unlock() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag(TestTags.Lock.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().performClick().performTextInput(V031Fixture.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performImeAction()
        awaitTag(listItem(TestTags.Gallery.TILE, 0), UNLOCK_WAIT_MS)
    }

    private fun fixtureEntryId(): String {
        val index = requireNotNull(app.graph.indexService.index())
        return index.query(GalleryFilter(GalleryScope.Home, GalleryView.JOURNAL, emptyList(), null, null, "")).single().entryIds.single()
    }

    private fun nextChooser(): Intent? = shadowOf(app).nextStartedActivity

    private fun assertTheFixturePhotoGoesStraightToTheChooser() {
        var sawSheet = false
        var chooser: Intent? = null
        compose.waitUntil(SHARE_WAIT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            sawSheet = sawSheet || exists(TestTags.Share.PICKER) || exists(TestTags.ShareEntries.SHEET)
            chooser = nextChooser()
            chooser != null
        }
        assertFalse(sawSheet)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        val send = requireNotNull(chooser!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertTrue(send.type, send.type!!.startsWith("image/"))
        assertNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { app.graph.navigator.routes.none { it is Route.ShareSheet } } }
        assertFalse(exists(TestTags.Share.PICKER))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theViewersShareActionOnALocalAccountHandsTheDecryptedPhotoStraightToTheChooserAndStaysOnTheViewer() {
        unlock()
        click(listItem(TestTags.Gallery.TILE, 0))
        awaitTag(TestTags.Viewer.SCREEN)
        click(TestTags.Viewer.SHARE_ACTION)
        assertTheFixturePhotoGoesStraightToTheChooser()
        awaitTag(TestTags.Viewer.SCREEN)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theEditPagesShareButtonOnALocalAccountGoesStraightToTheChooserAndStaysOnTheEditPage() {
        unlock()
        click(listItem(TestTags.Gallery.TILE, 0))
        awaitTag(TestTags.Viewer.SCREEN)
        click(TestTags.Viewer.EDIT)
        awaitTag(TestTags.Edit.SCREEN)
        click(TestTags.Edit.SHARE)
        assertTheFixturePhotoGoesStraightToTheChooser()
        awaitTag(TestTags.Edit.SCREEN)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theGallerysBulkShareOfOneEntryOnALocalAccountGoesStraightToTheChooser() {
        unlock()
        compose.onNodeWithTag(listItem(TestTags.Gallery.TILE, 0)).performTouchInput { longClick() }
        compose.waitForIdle()
        tap(TestTags.Gallery.ACTIONS)
        click(TestTags.Bulk.SHARE)
        assertTheFixturePhotoGoesStraightToTheChooser()
        awaitTag(TestTags.Gallery.SCREEN)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theSharedTabOnALocalAccountAsksToConnectAServer() {
        unlock()
        push(Route.Shared)
        awaitTag(TestTags.Shared.SCREEN)
        awaitTag(TestTags.Shared.FLAG_OFF)
        compose.onNodeWithTag(TestTags.Shared.FLAG_OFF, useUnmergedTree = true).assertTextEquals("Connect a server to share journals.")
        assertFalse(exists(TestTags.Shared.NEW))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theNewLinkRouteShowsThePickerForItsEntryAndCancelComesBack() {
        unlock()
        push(Route.LinkNew(listOf(fixtureEntryId())))
        awaitTag(TestTags.Links.CREATE_SCREEN)
        awaitTag(listItem(TestTags.Links.MEDIA_TOGGLE, 0))
        compose.onNodeWithTag(listItem(TestTags.Links.MEDIA_TOGGLE, 0)).performScrollTo().assertIsOn()
        assertFalse(exists(listItem(TestTags.Links.MEDIA_TOGGLE, 1)))
        compose.onNodeWithTag(TestTags.Links.INCLUDE_NOTES).performScrollTo().assertIsOn()
        compose.onNodeWithTag(TestTags.Links.EXPIRY + ".7d").performScrollTo()
        assertTrue(exists(TestTags.Links.WARNING))
        compose.onNodeWithTag(TestTags.Links.CREATE).performScrollTo().assertIsEnabled()
        click(TestTags.Links.CANCEL)
        awaitGone(TestTags.Links.CREATE_SCREEN)
        awaitTag(TestTags.Gallery.SCREEN)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theLinksJournalAndShareWithRoutesOpenTheirScreens() {
        unlock()
        push(Route.SettingsLinks)
        awaitTag(TestTags.Links.LIST_SCREEN)
        compose.waitUntil(WAIT_MS) { exists(TestTags.Links.LIST_EMPTY) || exists(TestTags.Links.ERROR) }
        tap(TestTags.Shared.BACK)
        awaitGone(TestTags.Links.LIST_SCREEN)
        push(Route.Collection(MISSING_JOURNAL))
        awaitTag(TestTags.Collection.SCREEN)
        awaitTag(TestTags.Shared.BACK)
        assertFalse(exists(TestTags.Collection.MORE))
        tap(TestTags.Shared.BACK)
        awaitGone(TestTags.Collection.SCREEN)
        push(Route.CollectionShare(MISSING_JOURNAL))
        awaitTag(TestTags.Share.SCREEN)
        awaitTag(TestTags.Share.USERNAME)
        compose.onNodeWithTag(TestTags.Share.FIND).assertIsNotEnabled()
        back()
        awaitGone(TestTags.Share.SCREEN)
        awaitTag(TestTags.Gallery.SCREEN)
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val SHARE_WAIT_MS = 30_000L
        const val UNLOCK_WAIT_MS = 120_000L
        const val MISSING_JOURNAL = "missingjournal00000001"
        const val FILE_PROVIDER_CACHE = "sCache"
    }
}

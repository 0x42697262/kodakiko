package dev.kodakiko.app.navigation

import android.view.WindowManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.app.FakeAndroidKeyStore
import dev.kodakiko.app.KodakikoApp
import dev.kodakiko.app.MainActivity
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.V031Fixture
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.media.ResolvedTime
import dev.kodakiko.media.TimeSource
import dev.kodakiko.service.entries.ImportedMedia
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class CaptureAndTagsWiringTest {
    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private val app: KodakikoApp get() = ApplicationProvider.getApplicationContext()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        FakeAndroidKeyStore.uninstall()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitTag(tag: String, timeoutMs: Long = WAIT_MS) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMs)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitGone(tag: String, timeoutMs: Long = WAIT_MS) {
        compose.waitUntilDoesNotExist(hasTestTag(tag), timeoutMs)
    }

    private fun count(tag: String): Int = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun exists(tag: String): Boolean = count(tag) > 0

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

    private fun contains(text: String): SemanticsMatcher = hasText(text) or hasAnyDescendant(hasText(text))

    private fun unlock() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag(TestTags.Lock.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().performClick().performTextInput(V031Fixture.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performImeAction()
        awaitTag(listItem(TestTags.Gallery.TILE, 0), UNLOCK_WAIT_MS)
    }

    private fun keepsScreenOn(): Boolean {
        var on = false
        scenario!!.onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    private fun trayItem(mediaId: String): ImportedMedia = ImportedMedia(
        mediaId = mediaId,
        mediaKey = ByteArray(KEY_BYTES) { 7 },
        originalObjectId = "$mediaId-original",
        thumbnailObjectId = "$mediaId-thumbnail",
        originalCiphertextBytes = 100,
        thumbnailCiphertextBytes = 10,
        descriptor = MediaDescriptorV1(
            mediaId = mediaId,
            kind = MediaKind.PHOTO,
            mimeType = "image/jpeg",
            width = 4000,
            height = 3000,
            durationMs = null,
            originalPlaintextBytes = 2_000_000,
            thumbnail = ThumbnailDescriptor(512, 384, 30_000),
            capturedAtUtc = null,
            originalFileName = null,
        ),
        time = ResolvedTime("2026-11-21T12:00:00.000Z", 60, TimeSource.NOW),
        location = null,
        thumbnailBytes = byteArrayOf(1, 2, 3),
    )

    @Test
    @Config(application = UpdatedV031App::class)
    fun theClearTrayLockStepEmptiesTheCaptureTrayAndWipesItsKeys() {
        unlock()
        val tray = app.graph.captureTray
        val item = trayItem("traywaitingforlock0000")
        tray.add(item)
        val generation = tray.generation
        tap(TestTags.Gallery.MORE)
        tap(TestTags.Lock.LOCK_NOW)
        awaitTag(TestTags.Lock.PASSWORD)
        compose.waitUntil(WAIT_MS) { tray.items.isEmpty() && item.mediaKey.all { it == 0.toByte() } }
        assertEquals(emptyList<ImportedMedia>(), tray.items)
        assertEquals(generation + 1, tray.generation)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theGalleryButtonOpensTheCaptureScreenWhichAsksForTheCameraAndCloseComesBack() {
        unlock()
        tap(TestTags.Gallery.CAPTURE)
        awaitTag(TestTags.Capture.SCREEN)
        awaitTag(TestTags.Capture.ALLOW_CAMERA)
        assertTrue(exists(TestTags.Capture.CAMERA_PERMISSION_BODY))
        assertTrue(exists(TestTags.Capture.PICK_FROM_GALLERY))
        assertTrue(exists(TestTags.Capture.NEXT))
        assertFalse(exists(TestTags.Capture.ADD_TO_ENTRY))
        assertTrue(keepsScreenOn())
        tap(TestTags.Capture.CLOSE)
        awaitTag(TestTags.Gallery.SCREEN)
        awaitGone(TestTags.Capture.SCREEN)
        assertFalse(keepsScreenOn())
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun editTagsFromTheEditPageAddsATagThatCanBeChosenAtOnce() {
        unlock()
        click(listItem(TestTags.Gallery.TILE, 0))
        awaitTag(TestTags.Viewer.SCREEN)
        click(TestTags.Viewer.EDIT)
        awaitTag(TestTags.Edit.SCREEN)
        click(TestTags.Tags.CHOOSE)
        awaitTag(TestTags.Tags.PICKER)
        assertFalse(exists(TestTags.Tags.OPTION + ".family"))
        tap(TestTags.Tags.EDIT_TAGS)
        awaitTag(TestTags.Tags.SCREEN)
        tap(TestTags.Tags.ADD)
        awaitTag(TestTags.Tags.NAME_FIELD)
        compose.onNodeWithTag(TestTags.Tags.NAME_FIELD).performTextReplacement("Family")
        tap(TestTags.Tags.SAVE)
        awaitGone(TestTags.Tags.NAME_FIELD)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasTestTag(listItem(TestTags.Tags.ROW, 0)) and contains("family")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(listItem(TestTags.Tags.ROW, 0)).assert(contains("0 entries"))
        tap(SETTINGS_BACK)
        awaitTag(TestTags.Edit.SCREEN)
        awaitGone(TestTags.Tags.SCREEN)
        click(TestTags.Tags.CHOOSE)
        tap(TestTags.Tags.OPTION + ".family")
        tap(TestTags.Tags.DONE)
        awaitTag(listItem(TestTags.Tags.CHIP, 0))
        compose.onNodeWithTag(listItem(TestTags.Tags.CHIP, 0), useUnmergedTree = true).assertTextEquals("family")
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun u31OpeningTheTagEditorFiveTimesQuicklyOpensItOnceAndOneBackLeavesIt() {
        unlock()
        scenario!!.onActivity { repeat(TAPS) { app.graph.navigator.push(Route.Tags) } }
        awaitTag(TestTags.Tags.SCREEN)
        compose.waitForIdle()
        assertEquals(1, count(TestTags.Tags.SCREEN))
        tap(SETTINGS_BACK)
        awaitTag(TestTags.Gallery.SCREEN)
        awaitGone(TestTags.Tags.SCREEN)
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val UNLOCK_WAIT_MS = 120_000L
        const val KEY_BYTES = 32
        const val TAPS = 5
        const val SETTINGS_BACK = "settings.back"
    }
}

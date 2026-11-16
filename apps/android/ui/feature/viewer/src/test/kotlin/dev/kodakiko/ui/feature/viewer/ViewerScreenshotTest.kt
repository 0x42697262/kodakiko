package dev.kodakiko.ui.feature.viewer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.ui.core.TestTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class ViewerScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun viewer(name: String, state: ViewerState, appTheme: AppTheme = AppTheme.LIGHT, images: ViewerImages = FakeViewerImages()) {
        compose.show(appTheme, images = images) { EntryViewerContent(state = state, onAction = {}) }
        compose.onRoot().captureScreenshot("viewer/$name")
    }

    private fun screen(name: String, state: ViewerState) {
        compose.show { EntryViewerContent(state = state, onAction = {}) }
        captureScreenScreenshot("viewer/$name")
    }

    @Test
    fun photoPage() = viewer("photo_page", ViewerStates.state())

    @Test
    fun fullEntry() = viewer("full_entry", ViewerStates.full)

    @Test
    fun fullEntryDark() = viewer("full_entry_dark", ViewerStates.full, AppTheme.DARK)

    @Test
    fun favorite() = viewer("favorite", ViewerStates.state(entry = ViewerStates.entry(body = ViewerStates.body(favorite = true, location = BERLIN))))

    @Test
    fun trashed() = viewer("trashed", ViewerStates.state(entry = ViewerStates.entry(trashed = true)))

    @Test
    fun unreadable() = viewer("unreadable", ViewerStates.state(entry = ViewerStates.entry(body = null, unreadable = true)))

    @Test
    fun sharedWithYou() = viewer("shared_with_you", ViewerStates.state(entry = ViewerStates.entry(owner = false, sharedBy = "bob")))

    @Test
    fun videoPageWithPlay() = viewer("video_page_play", ViewerStates.full.copy(page = 1))

    @Test
    fun inlineVideo() = viewer(
        "inline_video_muted",
        ViewerStates.full.copy(page = 1, video = InlineVideoUi(position = 1, mediaId = "m2", video = FakePreparedVideo("m2"), muted = true)),
    )

    @Test
    fun originalMissing() = viewer("original_missing", ViewerStates.state(), images = FakeViewerImages(originals = mapOf("p1" to OriginalImage.Missing)))

    @Test
    fun originalFailed() = viewer("original_failed", ViewerStates.state(), images = FakeViewerImages(originals = mapOf("p1" to OriginalImage.Failed)))

    @Test
    fun originalDownloading() = viewer(
        "original_downloading",
        ViewerStates.state(),
        images = FakeViewerImages(gates = mapOf("p1" to CompletableDeferred()), progress = mapOf("p1" to DownloadProgress(received = 420, total = 1000))),
    )

    @Test
    fun moreSheet() = screen("more_sheet", ViewerStates.state(sheet = ViewerSheet.MORE))

    @Test
    fun moreSheetSharingOff() = screen("more_sheet_sharing_off", ViewerStates.state(sheet = ViewerSheet.MORE, sharingAvailable = false, pinned = true))

    @Test
    fun confirmDelete() = screen("confirm_delete", ViewerStates.state(entry = ViewerStates.entry(trashed = true), sheet = ViewerSheet.CONFIRM_DELETE))

    @Test
    fun markdown() {
        compose.show {
            MarkdownView(
                source = "# Lake day\n\n## Morning\n\nWe **swam**, *read* and ~~worked~~ `nothing`.\nA second line.\n\n" +
                    "- towel\n- sunscreen\n  - spare\n\n3. three\n4. four\n\n- [x] packed\n- [ ] booked\n\n" +
                    "> quiet water\n\n---\n\n```\nval lake = 1\n```\n\n[a link](https://example.com) ![a heron](https://example.com/h.jpg)",
                onOpenLink = {},
                modifier = Modifier.padding(16.dp),
            )
        }
        compose.onRoot().captureScreenshot("viewer/markdown")
    }

    @Test
    fun linkConfirmSheet() {
        compose.show { MarkdownView(source = "Read [the docs](https://example.com/docs/very/long/path?with=query)", onOpenLink = {}) }
        MarkdownTesting.clickText(compose, "the docs")
        assertTrue(compose.exists(TestTags.Markdown.LINK_SHEET))
        captureScreenScreenshot("viewer/link_confirm_sheet")
    }

    @Test
    fun details() {
        compose.show { EntryDetailsContent(state = EntryDetailsState(sections = DETAIL_SECTIONS), onAction = {}) }
        compose.onRoot().captureScreenshot("viewer/details")
    }

    @Test
    fun playerPreparing() {
        compose.show { VideoPlayerContent(state = VideoPlayerState(progress = 0.42f, video = null, problem = null), onAction = {}) }
        compose.onRoot().captureScreenshot("viewer/player_preparing")
    }

    @Test
    fun playerProblem() {
        compose.show { VideoPlayerContent(state = VideoPlayerState(progress = 0f, video = null, problem = VideoProblem.DOWNLOAD_FAILED), onAction = {}) }
        compose.onRoot().captureScreenshot("viewer/player_problem")
    }

    @Test
    fun playerPlaying() {
        compose.show { VideoPlayerContent(state = VideoPlayerState(progress = 1f, video = FakePreparedVideo("m1"), problem = null), onAction = {}) }
        compose.onRoot().captureScreenshot("viewer/player_playing")
    }
}

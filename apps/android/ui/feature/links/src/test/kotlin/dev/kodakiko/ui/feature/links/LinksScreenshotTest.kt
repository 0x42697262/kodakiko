package dev.kodakiko.ui.feature.links

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.service.links.CreateLinkProgress
import dev.kodakiko.service.links.CreateLinkResult
import dev.kodakiko.service.links.CreateLinkStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class LinksScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sections = listOf(
        LinkSection(
            ENTRY_A,
            "2026-05-05 Secret picnic spot behind the old mill",
            listOf(LinkMedia("photogps", 0, MediaKind.PHOTO), LinkMedia("videogpsmov", 1, MediaKind.VIDEO), LinkMedia("video20s", 2, MediaKind.VIDEO)),
        ),
    )

    private fun form(progress: CreateLinkProgress? = null, error: LinkError? = null, result: CreateLinkResult? = null, copied: Boolean = false) =
        LinkCreateState(
            sections = sections,
            selected = setOf("photogps", "video20s"),
            title = DEFAULT_TITLE,
            includeNotes = false,
            expiry = LinkExpiry.SEVEN_DAYS,
            estimateBytes = 13 * MB + 70 * KB,
            progress = progress,
            result = result,
            error = error,
            copied = copied,
        )

    private val links = listOf(
        ownerLink("picnic00000000000000001", "Picnic", expiresAt = isoAt(NOW_MS + 3 * DAY_MS), viewCount = 2),
        ownerLink("party000000000000000002", "Party", maxViews = 1),
        ownerLink("trip0000000000000000003", "Trip", viewCount = 14),
    )

    private fun create(name: String, state: LinkCreateState, appTheme: AppTheme = AppTheme.LIGHT) {
        compose.show(appTheme) { LinkCreateContent(state = state, onAction = {}) }
        compose.onRoot().captureScreenshot("link_create/$name")
    }

    private fun list(name: String, state: LinksListState) {
        compose.show { LinksListContent(state = state, onAction = {}) }
        if (state.revoking == null) compose.onRoot().captureScreenshot("links_list/$name") else captureScreenScreenshot("links_list/$name")
    }

    @Test
    fun picker() = create("picker", form())

    @Test
    fun pickerDark() = create("picker_dark", form(), AppTheme.DARK)

    @Test
    fun uploading() = create("progress_uploading", form(progress = CreateLinkProgress(CreateLinkStep.UPLOADING, 1, 1, 3 * MB, 13 * MB)))

    @Test
    fun offline() = create("error_offline", form(error = LinkError.OFFLINE))

    @Test
    fun resultWithSkippedItems() = create("result_skipped", form(result = CreateLinkResult(LINK_URL, "l1", 1, 1, 2)))

    @Test
    fun resultCopied() = create("result_copied", form(result = CreateLinkResult(LINK_URL, "l1", 0, 0, 0), copied = true))

    @Test
    fun linksList() = list("rows", LinksListState(links = links, revoking = null, error = false, nowMs = NOW_MS))

    @Test
    fun noLinks() = list("empty", LinksListState(links = emptyList(), revoking = null, error = false, nowMs = NOW_MS))

    @Test
    fun revokeQuestion() = list("revoke_confirm", LinksListState(links = links, revoking = links.first(), error = false, nowMs = NOW_MS))

    @Test
    fun loadFailed() = list("error", LinksListState(links = null, revoking = null, error = true, nowMs = NOW_MS))
}

package dev.kodakiko.ui.feature.links

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.service.links.CreateLinkProgress
import dev.kodakiko.service.links.CreateLinkResult
import dev.kodakiko.service.links.CreateLinkStep
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h800dp-xhdpi")
class LinkCreateContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<LinkCreateAction>()

    private val sections = listOf(
        LinkSection(
            ENTRY_A,
            "2026-05-05 Secret picnic spot behind the old mill",
            listOf(LinkMedia("photogps", 0, MediaKind.PHOTO), LinkMedia("videogpsmov", 1, MediaKind.VIDEO), LinkMedia("video20s", 2, MediaKind.VIDEO)),
        ),
        LinkSection(ENTRY_B, "2026-05-06", listOf(LinkMedia("lake", 3, MediaKind.PHOTO))),
    )

    private fun form(
        selected: Set<String> = setOf("photogps", "videogpsmov", "video20s", "lake"),
        title: String = DEFAULT_TITLE,
        includeNotes: Boolean = true,
        expiry: LinkExpiry = LinkExpiry.SEVEN_DAYS,
        estimateBytes: Long = 35 * MB,
        progress: CreateLinkProgress? = null,
        error: LinkError? = null,
        sectionsShown: List<LinkSection>? = sections,
    ) = LinkCreateState(
        sections = sectionsShown,
        selected = selected,
        title = title,
        includeNotes = includeNotes,
        expiry = expiry,
        estimateBytes = estimateBytes,
        progress = progress,
        result = null,
        error = error,
        copied = false,
    )

    private fun done(result: CreateLinkResult, copied: Boolean = false) = form().copy(result = result, copied = copied)

    private val state = mutableStateOf(form())

    private fun render(initial: LinkCreateState) {
        state.value = initial
        compose.show { LinkCreateContent(state = state.value, onAction = { actions += it }) }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun theFormShowsTheTitleSectionsAndCheckedToggles() {
        render(form(selected = setOf("photogps", "video20s", "lake")))
        assertTrue(compose.exists(TestTags.Links.CREATE_SCREEN))
        assertTrue(compose.showsText("New Public Link"))
        compose.onNodeWithTag(TestTags.Links.TITLE).assert(contains(DEFAULT_TITLE))
        assertTrue(compose.showsText("2026-05-05 Secret picnic spot behind the old mill"))
        assertTrue(compose.showsText("2026-05-06"))
        compose.onNodeWithTag(listItem(TestTags.Links.MEDIA_TOGGLE, 0)).performScrollTo().assertIsOn()
        compose.onNodeWithTag(listItem(TestTags.Links.MEDIA_TOGGLE, 1)).performScrollTo().assertIsOff()
        compose.onNodeWithTag(listItem(TestTags.Links.MEDIA_TOGGLE, 2)).performScrollTo().assertIsOn()
        compose.onNodeWithTag(listItem(TestTags.Links.MEDIA_TOGGLE, 3)).performScrollTo().assertIsOn()
        assertFalse(compose.exists(listItem(TestTags.Links.MEDIA_TOGGLE, 4)))
        assertTrue(compose.exists(listItem(TestTags.Links.ENTRY_ALL, 1)))
        assertTrue(compose.exists(listItem(TestTags.Links.ENTRY_NONE, 1)))
        assertFalse(compose.exists(listItem(TestTags.Links.ENTRY_ALL, 2)))
    }

    @Test
    fun togglesAndSelectButtonsSendTheirActions() {
        render(form())
        click(listItem(TestTags.Links.MEDIA_TOGGLE, 1))
        click(listItem(TestTags.Links.MEDIA_TOGGLE, 3))
        click(listItem(TestTags.Links.ENTRY_NONE, 0))
        click(listItem(TestTags.Links.ENTRY_ALL, 1))
        click(TestTags.Links.SELECT_NONE)
        click(TestTags.Links.SELECT_ALL)
        assertEquals(
            listOf(
                LinkCreateAction.Toggle("videogpsmov"),
                LinkCreateAction.Toggle("lake"),
                LinkCreateAction.SelectEntry(ENTRY_A, on = false),
                LinkCreateAction.SelectEntry(ENTRY_B, on = true),
                LinkCreateAction.SelectNone,
                LinkCreateAction.SelectAll,
            ),
            actions,
        )
        compose.onNodeWithTag(TestTags.Links.SELECT_ALL).assert(labeled("Select All"))
        compose.onNodeWithTag(TestTags.Links.SELECT_NONE).assert(labeled("Select None"))
    }

    @Test
    fun includeNotesIsASwitchThatStartsOn() {
        render(form())
        compose.onNodeWithTag(TestTags.Links.INCLUDE_NOTES).performScrollTo().assertIsOn().assert(contains("Include notes"))
        click(TestTags.Links.INCLUDE_NOTES)
        assertEquals(listOf<LinkCreateAction>(LinkCreateAction.ToggleNotes), actions)
        state.value = form(includeNotes = false)
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Links.INCLUDE_NOTES).assertIsOff()
    }

    @Test
    fun theSevenExpiriesAreChipsWithSevenDaysChosen() {
        render(form())
        val labels = mapOf(
            LinkExpiry.ONE_HOUR to "1 hour",
            LinkExpiry.ONE_DAY to "1 day",
            LinkExpiry.SEVEN_DAYS to "7 days",
            LinkExpiry.FOURTEEN_DAYS to "14 days",
            LinkExpiry.THIRTY_DAYS to "30 days",
            LinkExpiry.NEVER to "Never",
            LinkExpiry.ONE_VIEW to "One view",
        )
        assertEquals(LinkExpiry.entries.toSet(), labels.keys)
        for ((expiry, label) in labels) {
            val node = compose.onNodeWithTag(TestTags.Links.EXPIRY + "." + expiry.wire).performScrollTo()
            node.assert(labeled(label))
            node.assert(if (expiry == LinkExpiry.SEVEN_DAYS) chosen() else notChosen())
        }
        click(TestTags.Links.EXPIRY + ".one_view")
        click(TestTags.Links.EXPIRY + ".1h")
        assertEquals(listOf(LinkCreateAction.ChooseExpiry(LinkExpiry.ONE_VIEW), LinkCreateAction.ChooseExpiry(LinkExpiry.ONE_HOUR)), actions)
    }

    @Test
    fun theEstimateAndTheStandingWarningAreShown() {
        render(form(estimateBytes = 13 * MB + 70 * KB))
        assertTrue(compose.showsText("About 13.1 MB to upload"))
        compose.onNodeWithTag(TestTags.Links.WARNING, useUnmergedTree = true).assert(
            contains("Anyone with the link can see what you selected. Location and photo/video details are removed. You can revoke the link later, but people may keep copies."),
        )
    }

    @Test
    fun theTitleFieldSendsItsText() {
        render(form())
        compose.onNodeWithTag(TestTags.Links.TITLE).performScrollTo().performTextReplacement("Picnic")
        compose.waitForIdle()
        assertEquals(listOf<LinkCreateAction>(LinkCreateAction.TitleChanged("Picnic")), actions)
    }

    @Test
    fun createNeedsACheckedItemAndATitle() {
        render(form())
        compose.onNodeWithTag(TestTags.Links.CREATE).performScrollTo().assert(labeled("Create Link")).assertIsEnabled()
        click(TestTags.Links.CREATE)
        assertEquals(listOf<LinkCreateAction>(LinkCreateAction.Create), actions)
        state.value = form(selected = emptySet())
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Links.CREATE).assertIsNotEnabled()
        state.value = form(title = "  ")
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Links.CREATE).assertIsNotEnabled()
    }

    @Test
    fun cancelLeaves() {
        render(form())
        compose.onNodeWithTag(TestTags.Links.CANCEL).performScrollTo().assert(labeled("Cancel"))
        click(TestTags.Links.CANCEL)
        assertEquals(listOf<LinkCreateAction>(LinkCreateAction.Cancel), actions)
    }

    @Test
    fun whileCreatingTheProgressStepShowsAndCreateIsDisabled() {
        render(form(progress = CreateLinkProgress(CreateLinkStep.PREPARING, 0, 2, 0, 0)))
        compose.onNodeWithTag(TestTags.Links.PROGRESS, useUnmergedTree = true).assert(contains("Preparing 1 of 2…"))
        compose.onNodeWithTag(TestTags.Links.CREATE).assertIsNotEnabled()
        state.value = form(progress = CreateLinkProgress(CreateLinkStep.UPLOADING, 2, 2, 3 * MB, 12 * MB))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Links.PROGRESS, useUnmergedTree = true).assert(contains("Uploading 3 MB of 12 MB…"))
        state.value = form(progress = CreateLinkProgress(CreateLinkStep.REGISTERING, 0, 1, 12 * MB, 12 * MB))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Links.PROGRESS, useUnmergedTree = true).assert(contains("Creating the link…"))
        state.value = form()
        compose.waitForIdle()
        assertFalse(compose.exists(TestTags.Links.PROGRESS))
    }

    @Test
    fun errorsReadLikeV031() {
        render(form(error = LinkError.OFFLINE))
        compose.onNodeWithTag(TestTags.Links.ERROR, useUnmergedTree = true).assert(contains("You're offline. Creating a link needs a connection."))
        state.value = form(error = LinkError.FAILED)
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Links.ERROR, useUnmergedTree = true).assert(contains("Couldn't create the link. Nothing was shared."))
    }

    @Test
    fun theResultShowsTheUrlWithCopyShareAndDone() {
        render(done(CreateLinkResult(LINK_URL, "l1", 0, 0, 0)))
        assertTrue(compose.showsText("Your link is ready."))
        compose.onNodeWithTag(TestTags.Links.URL, useUnmergedTree = true).assertTextEquals(LINK_URL)
        compose.onNodeWithTag(LinksTestTags.COPY).assert(labeled("Copy"))
        compose.onNodeWithTag(TestTags.Links.SHARE).assert(labeled("Share…"))
        compose.onNodeWithTag(TestTags.Links.DONE).assert(labeled("Done"))
        assertFalse(compose.exists(LinksTestTags.COPIED))
        assertFalse(compose.exists(TestTags.Links.CREATE))
        assertFalse(compose.exists(listItem(TestTags.Links.MEDIA_TOGGLE, 0)))
        click(LinksTestTags.COPY)
        click(TestTags.Links.SHARE)
        click(TestTags.Links.DONE)
        assertEquals(listOf(LinkCreateAction.Copy, LinkCreateAction.Share, LinkCreateAction.Done), actions)
    }

    @Test
    fun copiedIsConfirmedAndSkippedItemsAreListed() {
        render(done(CreateLinkResult(LINK_URL, "l1", 1, 2, 1), copied = true))
        compose.onNodeWithTag(LinksTestTags.COPIED, useUnmergedTree = true).assert(contains("Copied."))
        assertTrue(compose.showsText("1 entry was skipped."))
        assertTrue(compose.showsText("2 photos couldn't be converted."))
        assertTrue(compose.showsText("1 video is in a format links can't carry."))
    }

    @Test
    fun whileLoadingNoTogglesShow() {
        render(form(sectionsShown = null, selected = emptySet()))
        assertTrue(compose.exists(TestTags.Links.CREATE_SCREEN))
        assertFalse(compose.exists(listItem(TestTags.Links.MEDIA_TOGGLE, 0)))
    }

    @Test
    fun togglesLoadThumbnailsThroughThePort() {
        val thumbnails = FakeLinkThumbnails()
        state.value = form()
        compose.show {
            CompositionLocalProvider(LocalLinkThumbnails provides thumbnails) {
                LinkCreateContent(state = state.value, onAction = { actions += it })
            }
        }
        compose.waitUntil(WAIT_MS) { thumbnails.requests.isNotEmpty() }
        assertTrue(thumbnails.requests.toString(), (ENTRY_A to "photogps") in thumbnails.requests)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

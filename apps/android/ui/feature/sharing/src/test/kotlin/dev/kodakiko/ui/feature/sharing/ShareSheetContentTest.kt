package dev.kodakiko.ui.feature.sharing

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.kodakiko.service.shareout.SharePickItem
import dev.kodakiko.service.sharing.AddEntriesResult
import dev.kodakiko.service.sharing.CollectionName
import dev.kodakiko.service.sharing.ShareItem
import dev.kodakiko.service.sharing.ShareSelection
import dev.kodakiko.service.sharing.ShareTextOption
import dev.kodakiko.service.sharing.shareSelection
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
class ShareSheetContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<ShareSheetAction>()

    private val pair = listOf(
        SharePickItem("pair", photo("p1", 2 * MB, "Beach"), 0, "pair"),
        SharePickItem("pair", photo("p2", 3 * MB), 1, "pair"),
    )

    private fun selectionOf(items: List<SharePickItem>, chosen: Set<String>): ShareSelection =
        shareSelection(items.map { ShareItem(it.media.mediaId, it.media.originalPlaintextBytes, it.media.caption) }, chosen)

    private fun apps(
        items: List<SharePickItem>? = pair,
        chosen: Set<String> = items.orEmpty().map { it.media.mediaId }.toSet(),
        option: ShareTextOption = ShareTextOption.NONE,
        busy: Boolean = false,
        failed: Boolean = false,
    ) = ToAppsState(items = items, chosen = chosen, option = option, selection = selectionOf(items.orEmpty(), chosen), busy = busy, failed = failed)

    private fun kodakiko(
        panel: InKodakikoPanel = InKodakikoPanel.MENU,
        sharingEnabled: Boolean = true,
        linksEnabled: Boolean = true,
        journals: List<JournalChoice> = emptyList(),
        name: String = "",
        busy: Boolean = false,
        outcome: ShareEntriesOutcome? = null,
    ) = InKodakikoState(panel = panel, sharingEnabled = sharingEnabled, linksEnabled = linksEnabled, journals = journals, name = name, busy = busy, outcome = outcome)

    private fun sheet(inKodakiko: Boolean = true, tab: ShareTab = ShareTab.IN_KODAKIKO, kodakiko: InKodakikoState = kodakiko(), apps: ToAppsState = apps()) =
        ShareSheetState(inKodakiko = inKodakiko, tab = tab, kodakiko = kodakiko, apps = apps)

    private val state = mutableStateOf(sheet())

    private fun render(initial: ShareSheetState) {
        state.value = initial
        compose.show { ShareSheetContent(state = state.value, onAction = { actions += it }) }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun checkbox(): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    @Test
    fun withInKodakikoBothTabsShowAndInKodakikoIsTheMenu() {
        render(sheet())
        compose.onNodeWithTag(SharingTestTags.TAB_KODAKIKO).assert(labeled("In Kodakiko")).assert(chosen())
        compose.onNodeWithTag(SharingTestTags.TAB_APPS).assert(labeled("To apps")).assert(notChosen())
        assertTrue(compose.exists(TestTags.ShareEntries.SHEET))
        compose.onNodeWithTag(TestTags.ShareEntries.ADD_TO).assert(labeled("Add to Shared Journal…")).assertIsEnabled()
        compose.onNodeWithTag(TestTags.ShareEntries.CREATE_LINK).assert(labeled("Create Public Link…")).assertIsEnabled()
        assertFalse(compose.exists(TestTags.Shared.SHARING_OFF_HINT))
        assertFalse(compose.exists(TestTags.Links.OFF_HINT))
        assertFalse(compose.exists(TestTags.Share.PICKER))
    }

    @Test
    fun tabsSwitchByAction() {
        render(sheet())
        click(SharingTestTags.TAB_APPS)
        click(SharingTestTags.TAB_KODAKIKO)
        assertEquals(listOf(ShareSheetAction.SelectTab(ShareTab.TO_APPS), ShareSheetAction.SelectTab(ShareTab.IN_KODAKIKO)), actions)
    }

    @Test
    fun withoutInKodakikoThereAreNoTabsOnlyTheToAppsTitleAndThePicker() {
        render(sheet(inKodakiko = false, tab = ShareTab.TO_APPS))
        assertFalse(compose.exists(SharingTestTags.TAB_KODAKIKO))
        assertFalse(compose.exists(SharingTestTags.TAB_APPS))
        assertFalse(compose.exists(TestTags.ShareEntries.SHEET))
        assertTrue(compose.exists(TestTags.Share.PICKER))
        assertTrue(compose.showsText("To apps"))
    }

    @Test
    fun featuresTurnedOffDisableTheirActionsWithAHint() {
        render(sheet(kodakiko = kodakiko(sharingEnabled = false, linksEnabled = false)))
        compose.onNodeWithTag(TestTags.ShareEntries.ADD_TO).assertIsNotEnabled()
        compose.onNodeWithTag(TestTags.Shared.SHARING_OFF_HINT, useUnmergedTree = true).assert(contains("Sharing with people on this server is turned off."))
        compose.onNodeWithTag(TestTags.ShareEntries.CREATE_LINK).assertIsNotEnabled()
        compose.onNodeWithTag(TestTags.Links.OFF_HINT, useUnmergedTree = true).assert(contains("Public links are turned off on this server."))
    }

    @Test
    fun menuActionsAreSent() {
        render(sheet())
        click(TestTags.ShareEntries.ADD_TO)
        click(TestTags.ShareEntries.CREATE_LINK)
        assertEquals(listOf(ShareSheetAction.AddTo, ShareSheetAction.CreateLink), actions)
    }

    @Test
    fun theJournalPanelListsOwnJournalsAndANewOne() {
        render(
            sheet(
                kodakiko = kodakiko(
                    panel = InKodakikoPanel.JOURNALS,
                    journals = listOf(JournalChoice(PICNICS, CollectionName.Named("Picnics")), JournalChoice(TRIPS, CollectionName.Unnamed)),
                ),
            ),
        )
        compose.onNodeWithTag(listItem(TestTags.ShareEntries.JOURNAL, 0)).assert(labeled("Picnics"))
        compose.onNodeWithTag(listItem(TestTags.ShareEntries.JOURNAL, 1)).assert(labeled("Untitled journal"))
        compose.onNodeWithTag(TestTags.ShareEntries.NEW_JOURNAL).assert(labeled("New Shared Journal…"))
        assertFalse(compose.exists(TestTags.ShareEntries.ADD_TO))
        click(listItem(TestTags.ShareEntries.JOURNAL, 1))
        click(TestTags.ShareEntries.NEW_JOURNAL)
        assertEquals(listOf(ShareSheetAction.AddToJournal(TRIPS), ShareSheetAction.NewJournal), actions)
    }

    @Test
    fun theNewJournalPanelNeedsANameToCreate() {
        render(sheet(kodakiko = kodakiko(panel = InKodakikoPanel.NEW)))
        compose.onNodeWithTag(TestTags.Shared.CREATE).assert(labeled("Create")).assertIsNotEnabled()
        compose.onNodeWithTag(TestTags.Shared.NAME_FIELD).performTextReplacement("Picnics")
        compose.waitForIdle()
        state.value = sheet(kodakiko = kodakiko(panel = InKodakikoPanel.NEW, name = "Picnics"))
        compose.waitForIdle()
        click(TestTags.Shared.CREATE)
        assertEquals(listOf(ShareSheetAction.NameChanged("Picnics"), ShareSheetAction.CreateJournal), actions)
    }

    @Test
    fun theResultOfAddingSummarizesWhatHappened() {
        val result = AddEntriesResult(added = listOf("e1", "e2"), alreadyIn = emptyList(), notSynced = listOf("e3"), failed = emptyList())
        render(sheet(kodakiko = kodakiko(outcome = ShareEntriesOutcome.Added(result))))
        compose.onNodeWithTag(TestTags.ShareEntries.RESULT, useUnmergedTree = true).assert(contains("2 added. 1 not synced yet — try again shortly."))
        state.value = sheet(kodakiko = kodakiko(panel = InKodakikoPanel.JOURNALS, outcome = ShareEntriesOutcome.Failed(SharingMessage.Offline)))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.ShareEntries.RESULT, useUnmergedTree = true).assert(contains("You're offline. Sharing needs a connection."))
    }

    @Test
    fun u39ThePickerStartsWithEveryItemCheckedAndTheCountInTheButton() {
        render(sheet(tab = ShareTab.TO_APPS))
        compose.onNodeWithTag(SharingTestTags.TAB_APPS).assert(chosen())
        assertTrue(compose.exists(TestTags.Share.PICKER))
        compose.onNodeWithTag(listItem(TestTags.Share.ITEM, 0)).assert(checkbox()).assertIsOn().assert(hasContentDescription("Item 1, selected"))
        compose.onNodeWithTag(listItem(TestTags.Share.ITEM, 1)).assertIsOn().assert(hasContentDescription("Item 2, selected"))
        assertFalse(compose.exists(listItem(TestTags.Share.ITEM, 2)))
        compose.onNodeWithTag(TestTags.Share.SEND).assert(labeled("Share 2 items · 5 MB")).assertIsEnabled()
        compose.onNodeWithTag(TestTags.Share.LEAVES_ENCRYPTION, useUnmergedTree = true)
            .assert(contains("Shared files leave Kodakiko unencrypted. The receiving app can read them."))
        assertFalse(compose.exists(TestTags.Share.TOO_BIG))
        assertFalse(compose.exists(TestTags.Share.FAILED))
    }

    @Test
    fun u39AnUncheckedItemIsOffAndTheButtonCountsOne() {
        render(sheet(tab = ShareTab.TO_APPS, apps = apps(chosen = setOf("p1"))))
        compose.onNodeWithTag(listItem(TestTags.Share.ITEM, 1)).assertIsOff().assert(hasContentDescription("Item 2"))
        compose.onNodeWithTag(TestTags.Share.SEND).assert(labeled("Share 1 item · 2 MB"))
        click(listItem(TestTags.Share.ITEM, 1))
        click(listItem(TestTags.Share.ITEM, 0))
        assertEquals(listOf(ShareSheetAction.Toggle("p2"), ShareSheetAction.Toggle("p1")), actions)
    }

    @Test
    fun nothingCheckedDisablesSend() {
        render(sheet(tab = ShareTab.TO_APPS, apps = apps(chosen = emptySet())))
        compose.onNodeWithTag(TestTags.Share.SEND).assert(labeled("Share 0 items · 0 B")).assertIsNotEnabled()
    }

    @Test
    fun theTextOptionsAreNoneCaptionsAndCaptionsAndNote() {
        render(sheet(tab = ShareTab.TO_APPS, apps = apps(option = ShareTextOption.CAPTIONS)))
        compose.onNodeWithTag(SharingTestTags.TEXT_NONE).assert(labeled("None")).assert(notChosen())
        compose.onNodeWithTag(SharingTestTags.TEXT_CAPTIONS).assert(labeled("Captions")).assert(chosen())
        compose.onNodeWithTag(SharingTestTags.TEXT_CAPTIONS_AND_NOTE).assert(labeled("Captions and note")).assert(notChosen())
        click(SharingTestTags.TEXT_CAPTIONS_AND_NOTE)
        click(SharingTestTags.TEXT_NONE)
        assertEquals(
            listOf(ShareSheetAction.ChooseText(ShareTextOption.CAPTIONS_AND_NOTE), ShareSheetAction.ChooseText(ShareTextOption.NONE)),
            actions,
        )
    }

    @Test
    fun aLargeSelectionWarnsWithItsSize() {
        val big = listOf(SharePickItem("big", video("v1", 600 * MB), 0, ""))
        render(sheet(tab = ShareTab.TO_APPS, apps = apps(items = big)))
        compose.onNodeWithTag(TestTags.Share.TOO_BIG, useUnmergedTree = true)
            .assert(contains("That is 600 MB. Decrypting and sharing it can take a while."))
        compose.onNodeWithTag(TestTags.Share.SEND).assert(labeled("Share 1 item · 600 MB"))
    }

    @Test
    fun sendIsAnActionAndIsDisabledWhileBusy() {
        render(sheet(tab = ShareTab.TO_APPS))
        click(TestTags.Share.SEND)
        assertEquals(listOf<ShareSheetAction>(ShareSheetAction.Send), actions)
        state.value = sheet(tab = ShareTab.TO_APPS, apps = apps(busy = true))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Share.SEND).assertIsNotEnabled()
    }

    @Test
    fun aFailedShareShowsItsMessage() {
        render(sheet(tab = ShareTab.TO_APPS, apps = apps(failed = true)))
        compose.onNodeWithTag(TestTags.Share.FAILED, useUnmergedTree = true).assert(contains("Couldn't share these files."))
        state.value = sheet(tab = ShareTab.TO_APPS, apps = apps(items = null, failed = true))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Share.FAILED, useUnmergedTree = true).assert(contains("Couldn't share these files."))
    }

    @Test
    fun thePickerLoadsThumbnailsThroughThePort() {
        val thumbnails = FakeThumbnails()
        state.value = sheet(tab = ShareTab.TO_APPS)
        compose.show {
            CompositionLocalProvider(LocalSharingThumbnails provides thumbnails) {
                ShareSheetContent(state = state.value, onAction = { actions += it })
            }
        }
        compose.waitUntil(WAIT_MS) { thumbnails.requests.distinct().size == 2 }
        assertEquals(setOf("pair" to "p1", "pair" to "p2"), thumbnails.requests.toSet())
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

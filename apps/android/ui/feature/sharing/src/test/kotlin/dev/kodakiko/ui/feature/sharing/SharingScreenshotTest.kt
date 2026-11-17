package dev.kodakiko.ui.feature.sharing

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.service.shareout.SharePickItem
import dev.kodakiko.service.sharing.AddEntriesResult
import dev.kodakiko.service.sharing.CollectionName
import dev.kodakiko.service.sharing.ShareItem
import dev.kodakiko.service.sharing.ShareTextOption
import dev.kodakiko.service.sharing.shareSelection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class SharingScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mine = listOf(journal(PICNICS, "Picnics", count = 2), journal(TRIPS, null, count = 1))
    private val withMe = listOf(journal(FROM_BOB, "Hikes", CollectionRole.VIEWER, "bob", count = 12))
    private val tiles = listOf(CollectionTile("e3", null), CollectionTile("e2", null), CollectionTile("e1", null))

    private fun capture(name: String, overlay: Boolean, appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
        compose.show(appTheme) { content() }
        if (overlay) captureScreenScreenshot(name) else compose.onRoot().captureScreenshot(name)
    }

    private fun shared(name: String, state: SharedState, appTheme: AppTheme = AppTheme.LIGHT) =
        capture("shared/$name", state.dialog != null, appTheme) { SharedContent(state = state, onAction = {}) }

    private fun collection(name: String, state: CollectionState) =
        capture("collection/$name", state.sheet != null) { CollectionContent(state = state, onAction = {}) }

    private fun shareWith(name: String, state: ShareWithState) =
        capture("share_with/$name", state.stopping != null) { ShareWithContent(state = state, onAction = {}) }

    private fun sheet(name: String, state: ShareSheetState) = capture("share_sheet/$name", false) { ShareSheetContent(state = state, onAction = {}) }

    private fun enabled(dialog: SharedDialog? = null) =
        SharedState(availability = SharedAvailability.ENABLED, mine = mine, withMe = withMe, dialog = dialog, refreshing = false)

    private fun owner(sheet: CollectionSheet? = null, selecting: Boolean = false, selected: List<String> = emptyList()) =
        CollectionState(loaded = true, journal = journal(PICNICS, "Picnics", count = 3), tiles = tiles, selecting = selecting, selected = selected, sheet = sheet)

    private fun recipient(sheet: CollectionSheet? = null) = CollectionState(
        loaded = true,
        journal = journal(FROM_BOB, "Hikes", CollectionRole.VIEWER, "bob", count = 3),
        tiles = tiles,
        selecting = false,
        selected = emptyList(),
        sheet = sheet,
    )

    private val pickItems = listOf(
        SharePickItem("pair", photo("p1", 2 * MB, "Beach"), 0, "pair"),
        SharePickItem("pair", photo("p2", 3 * MB), 1, "pair"),
        SharePickItem("pair", video("v1", 20 * MB), 2, "pair"),
    )

    private fun apps(items: List<SharePickItem> = pickItems, chosen: Set<String> = setOf("p1", "v1"), option: ShareTextOption = ShareTextOption.CAPTIONS) =
        ToAppsState(
            items = items,
            chosen = chosen,
            option = option,
            selection = shareSelection(items.map { ShareItem(it.media.mediaId, it.media.originalPlaintextBytes, it.media.caption) }, chosen),
            busy = false,
            failed = false,
        )

    private fun kodakiko(
        panel: InKodakikoPanel = InKodakikoPanel.MENU,
        sharingEnabled: Boolean = true,
        linksEnabled: Boolean = true,
        outcome: ShareEntriesOutcome? = null,
    ) = InKodakikoState(
        panel = panel,
        sharingEnabled = sharingEnabled,
        linksEnabled = linksEnabled,
        journals = listOf(JournalChoice(PICNICS, CollectionName.Named("Picnics")), JournalChoice(TRIPS, CollectionName.Named("Trips"))),
        name = "",
        busy = false,
        outcome = outcome,
    )

    @Test
    fun sharedTab() = shared("enabled", enabled())

    @Test
    fun sharedTabDark() = shared("enabled_dark", enabled(), AppTheme.DARK)

    @Test
    fun sharedTabEmpty() = shared("empty", SharedState(SharedAvailability.ENABLED, emptyList(), emptyList(), null, false))

    @Test
    fun sharedTabFlagOff() = shared("flag_off", SharedState(SharedAvailability.FLAG_OFF, null, null, null, false))

    @Test
    fun sharedTabLocalOnly() = shared("local_only", SharedState(SharedAvailability.LOCAL_ONLY, null, null, null, false))

    @Test
    fun newJournalWithAnError() = shared("new_journal_error", enabled(SharedDialog.New(name = "Picnics", busy = false, error = SharingMessage.Offline)))

    @Test
    fun ownersJournal() = collection("owner", owner())

    @Test
    fun recipientsJournal() = collection("recipient", recipient())

    @Test
    fun ownersMenu() = collection("menu_owner", owner(CollectionSheet.Menu))

    @Test
    fun recipientsMenu() = collection("menu_recipient", recipient(CollectionSheet.Menu))

    @Test
    fun leaveConfirmation() = collection("confirm_leave", recipient(CollectionSheet.ConfirmLeave(busy = false, error = null)))

    @Test
    fun selectingEntries() = collection("selecting", owner(selecting = true, selected = listOf("e2")))

    @Test
    fun shareWithTheCodeShown() = shareWith(
        "found",
        ShareWithState(
            username = "bob",
            found = FoundRecipient("bob", "9f86 d081 884c 7d65"),
            recipients = listOf(shareDto(CAROL_ID, "carol")),
            stopping = null,
            busy = false,
            error = null,
        ),
    )

    @Test
    fun shareWithAnUnknownUser() = shareWith(
        "user_not_found",
        ShareWithState(username = "nobody", found = null, recipients = emptyList(), stopping = null, busy = false, error = SharingMessage.UserNotFound),
    )

    @Test
    fun shareWithStopConfirmation() = shareWith(
        "stop_confirm",
        ShareWithState(
            username = "",
            found = null,
            recipients = listOf(shareDto(BOB_ID, "bob"), shareDto(CAROL_ID, "carol")),
            stopping = shareDto(BOB_ID, "bob"),
            busy = false,
            error = null,
        ),
    )

    @Test
    fun shareSheetInKodakiko() = sheet("in_kodakiko", ShareSheetState(true, ShareTab.IN_KODAKIKO, kodakiko(), apps()))

    @Test
    fun shareSheetFeaturesOff() =
        sheet("features_off", ShareSheetState(true, ShareTab.IN_KODAKIKO, kodakiko(sharingEnabled = false, linksEnabled = false), apps()))

    @Test
    fun shareSheetJournals() = sheet("journals", ShareSheetState(true, ShareTab.IN_KODAKIKO, kodakiko(panel = InKodakikoPanel.JOURNALS), apps()))

    @Test
    fun shareSheetResult() = sheet(
        "result",
        ShareSheetState(
            true,
            ShareTab.IN_KODAKIKO,
            kodakiko(outcome = ShareEntriesOutcome.Added(AddEntriesResult(listOf("e1", "e2"), listOf("e3"), listOf("e4"), emptyList()))),
            apps(),
        ),
    )

    @Test
    fun shareSheetToApps() = sheet("to_apps", ShareSheetState(true, ShareTab.TO_APPS, kodakiko(), apps()))

    @Test
    fun shareSheetToAppsOnly() = sheet("to_apps_only", ShareSheetState(false, ShareTab.TO_APPS, kodakiko(), apps(chosen = setOf("p1", "p2", "v1"), option = ShareTextOption.NONE)))

    @Test
    fun shareSheetTooBig() = sheet(
        "to_apps_too_big",
        ShareSheetState(
            false,
            ShareTab.TO_APPS,
            kodakiko(),
            apps(items = listOf(SharePickItem("big", video("v9", 600 * MB), 0, "")), chosen = setOf("v9"), option = ShareTextOption.NONE),
        ),
    )
}

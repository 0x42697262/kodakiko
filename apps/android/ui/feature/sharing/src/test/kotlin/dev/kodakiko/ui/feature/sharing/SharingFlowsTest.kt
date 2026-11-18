package dev.kodakiko.ui.feature.sharing

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.publicKeyFingerprint
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.service.sharing.Recipient
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator
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
class SharingFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SharingHarness()
    private var sheetViewModel: ShareSheetViewModel? = null

    private inner class StackNavigator(vararg start: Route) : RouteNavigator {
        val stack = mutableStateListOf(*start)
        val log = mutableListOf<String>()

        override val routes: List<Route> get() = stack.toList()

        override fun push(route: Route) {
            log += "push ${route.path}"
            stack.add(route)
        }

        override fun pop(): Boolean {
            log += "pop"
            if (stack.size <= 1) return false
            stack.removeAt(stack.lastIndex)
            return true
        }

        override fun replace(route: Route) {
            log += "replace ${route.path}"
            stack[stack.lastIndex] = route
        }

        override fun replaceAll(route: Route) {
            log += "replaceAll ${route.path}"
            stack.clear()
            stack.add(route)
        }
    }

    @Composable
    private fun Screen(route: Route) {
        when (route) {
            Route.Shared -> SharedScreen(viewModel = remember { SharedViewModel(h.deps) })
            is Route.Collection -> CollectionScreen(viewModel = remember { CollectionViewModel(h.deps, route.collectionId) })
            is Route.CollectionShare -> ShareWithScreen(viewModel = remember { ShareWithViewModel(h.deps, route.collectionId) })
            is Route.ShareSheet -> ShareSheetScreen(viewModel = remember { ShareSheetViewModel(h.deps, route.entryIds, route.inKodakiko).also { sheetViewModel = it } })
            else -> Unit
        }
    }

    private fun start(navigator: StackNavigator) {
        compose.show {
            CompositionLocalProvider(LocalRouteNavigator provides navigator) {
                val top = navigator.stack.last()
                key(navigator.stack.size, top) { Screen(top) }
            }
        }
    }

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    private fun click(tag: String) {
        await(tag)
        node(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        await(tag)
        node(tag).performClick()
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun u17TheRecipientBrowsesTheJournalAndLeavingRemovesIt() {
        h.bobsJournalWithTwoEntries()
        val nav = StackNavigator(Route.Shared)
        start(nav)
        await(listItem(TestTags.Shared.WITH_ME_ROW, 0))
        node(listItem(TestTags.Shared.WITH_ME_ROW, 0)).assert(contains("Picnics"))
        click(listItem(TestTags.Shared.WITH_ME_ROW, 0))
        assertEquals(Route.Collection(FROM_BOB), nav.stack.last())
        await(listItem(SharingTestTags.GALLERY_TILE, 1))
        assertFalse(compose.exists(listItem(SharingTestTags.GALLERY_TILE, 2)))
        compose.onNodeWithTag(TestTags.Collection.SUBTITLE, useUnmergedTree = true).assertTextEquals("Shared by bob")
        click(listItem(SharingTestTags.GALLERY_TILE, 0))
        assertEquals(Route.Entry("b2"), nav.stack.last())
        compose.runOnUiThread { nav.pop() }
        compose.waitForIdle()
        tap(TestTags.Collection.MORE)
        tap(TestTags.Collection.LEAVE)
        tap(TestTags.Collection.LEAVE_CONFIRM)
        compose.waitUntil(WAIT_MS) { nav.stack.last() == Route.Shared }
        await(TestTags.Shared.EMPTY_WITH_ME)
        assertEquals(listOf(SharingCall(FakeSharingService.LEAVE, FROM_BOB)), h.sharing.calls)
    }

    @Test
    fun u17TheOwnerFindsTheRecipientAndReadsTheSameCodeTheRecipientSeesInSettings() {
        val sodium = createSodium()
        val bobKey = sodium.boxKeypair().publicKey
        val bobCode = publicKeyFingerprint(sodium, bobKey)
        h.sharing.users["bob"] = Recipient(BOB_ID, "bob", bobKey, bobCode)
        h.picnicsWithTwoEntries()
        val nav = StackNavigator(Route.Shared)
        start(nav)
        click(listItem(TestTags.Shared.MINE_ROW, 0))
        tap(TestTags.Collection.MORE)
        tap(TestTags.Collection.SHARE_WITH)
        assertEquals(Route.CollectionShare(PICNICS), nav.stack.last())
        await(TestTags.Share.USERNAME)
        node(TestTags.Share.USERNAME).performTextReplacement("bob")
        compose.waitForIdle()
        click(TestTags.Share.FIND)
        await(TestTags.Share.SECURITY_CODE)
        compose.onNodeWithTag(TestTags.Share.SECURITY_CODE, useUnmergedTree = true).assertTextEquals(publicKeyFingerprint(sodium, bobKey))
        click(TestTags.Share.CONFIRM)
        await(listItem(TestTags.Share.RECIPIENT_ROW, 0))
        node(listItem(TestTags.Share.RECIPIENT_ROW, 0)).assert(contains("bob"))
        assertFalse(compose.exists(TestTags.Share.SECURITY_CODE))
        click(listItem(TestTags.Share.REMOVE, 0))
        tap(TestTags.Share.STOP_CONFIRM)
        compose.waitUntil(WAIT_MS) { !compose.exists(listItem(TestTags.Share.RECIPIENT_ROW, 0)) }
        tap(TestTags.Shared.BACK)
        assertEquals(Route.Collection(PICNICS), nav.stack.last())
    }

    @Test
    fun aNewJournalFromTheSharedTabOpensAtOnce() {
        val nav = StackNavigator(Route.Shared)
        start(nav)
        click(TestTags.Shared.NEW)
        await(TestTags.Shared.NAME_FIELD)
        node(TestTags.Shared.NAME_FIELD).performTextReplacement("Picnics")
        compose.waitForIdle()
        tap(TestTags.Shared.CREATE)
        compose.waitUntil(WAIT_MS) { nav.stack.last() == Route.Collection(h.sharing.nextId) }
        await(TestTags.Collection.TITLE)
        compose.onNodeWithTag(TestTags.Collection.TITLE, useUnmergedTree = true).assertTextEquals("Picnics")
    }

    @Test
    fun deletingAJournalOpenedWithoutAHistoryFallsBackToTheSharedTab() {
        h.picnicsWithTwoEntries()
        val nav = StackNavigator(Route.Collection(PICNICS))
        start(nav)
        tap(TestTags.Collection.MORE)
        tap(TestTags.Collection.DELETE)
        tap(TestTags.Collection.DELETE_CONFIRM)
        compose.waitUntil(WAIT_MS) { nav.stack.toList() == listOf<Route>(Route.Shared) }
        assertEquals(listOf("pop", "replaceAll /shared"), nav.log)
    }

    @Test
    fun removingSelectedEntriesShowsHowManyWereUpdated() {
        h.picnicsWithTwoEntries()
        val nav = StackNavigator(Route.Shared, Route.Collection(PICNICS))
        start(nav)
        await(listItem(SharingTestTags.GALLERY_TILE, 0))
        node(listItem(SharingTestTags.GALLERY_TILE, 0)).performTouchInput { longClick() }
        compose.waitForIdle()
        tap(listItem(SharingTestTags.GALLERY_TILE, 1))
        tap(SharingTestTags.GALLERY_ACTIONS)
        tap(SharingTestTags.REMOVE_FROM_JOURNAL)
        compose.waitUntil(WAIT_MS) { compose.showsText("2 entries updated.") }
        assertEquals(listOf(SharingCall(FakeSharingService.REMOVE, PICNICS, listOf("e2", "e1"))), h.sharing.calls)
    }

    @Test
    fun systemBackEndsTheSelectionAndStaysInTheJournal() {
        h.picnicsWithTwoEntries()
        val nav = StackNavigator(Route.Shared, Route.Collection(PICNICS))
        start(nav)
        await(listItem(SharingTestTags.GALLERY_TILE, 0))
        node(listItem(SharingTestTags.GALLERY_TILE, 0)).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(compose.exists(SharingTestTags.GALLERY_ACTIONS))
        systemBack()
        assertFalse(compose.exists(SharingTestTags.GALLERY_ACTIONS))
        assertEquals(Route.Collection(PICNICS), nav.stack.last())
        assertEquals(emptyList<String>(), nav.log)
    }

    @Test
    fun u39TheShareSheetPickerStartsAllCheckedAndDismissingClosesIt() {
        h.pairEntry()
        val nav = StackNavigator(Route.Entry("pair"), Route.ShareSheet(listOf("pair"), true))
        start(nav)
        tap(SharingTestTags.TAB_APPS)
        await(listItem(TestTags.Share.ITEM, 1))
        node(listItem(TestTags.Share.ITEM, 0)).assertIsOn()
        node(listItem(TestTags.Share.ITEM, 1)).assertIsOn()
        node(TestTags.Share.SEND).assert(contains("Share 2 items"))
        click(listItem(TestTags.Share.ITEM, 1))
        node(listItem(TestTags.Share.ITEM, 1)).assertIsOff()
        node(TestTags.Share.SEND).assert(contains("Share 1 item"))
        assertTrue(compose.exists(TestTags.Share.LEAVES_ENCRYPTION))
        click(TestTags.Share.SEND)
        compose.waitUntil(WAIT_MS) { h.shareOut.shared.isNotEmpty() }
        assertEquals(setOf("p1"), h.shareOut.shared.single().chosen)
        compose.runOnUiThread { requireNotNull(sheetViewModel).onAction(ShareSheetAction.Dismiss) }
        compose.waitUntil(WAIT_MS) { nav.stack.last() == Route.Entry("pair") }
        assertEquals(listOf("pop"), nav.log)
        assertFalse(compose.exists(TestTags.Share.PICKER))
    }

    @Test
    fun createPublicLinkReplacesTheSheetSoBackFromTheLinkScreenReturnsToTheEntry() {
        h.pairEntry()
        val nav = StackNavigator(Route.Entry("pair"), Route.ShareSheet(listOf("pair"), true))
        start(nav)
        tap(TestTags.ShareEntries.CREATE_LINK)
        compose.waitUntil(WAIT_MS) { nav.stack.last() is Route.LinkNew }
        assertEquals(listOf(Route.Entry("pair"), Route.LinkNew(listOf("pair"))), nav.stack.toList())
    }

    @Test
    fun addingToANewJournalFromTheSheetReportsTheResult() {
        h.pairEntry()
        h.sharing.addResult = h.sharing.addResult.copy(added = listOf("pair"))
        val nav = StackNavigator(Route.Entry("pair"), Route.ShareSheet(listOf("pair"), true))
        start(nav)
        tap(TestTags.ShareEntries.ADD_TO)
        tap(TestTags.ShareEntries.NEW_JOURNAL)
        await(TestTags.Shared.NAME_FIELD)
        node(TestTags.Shared.NAME_FIELD).performTextReplacement("Picnics")
        compose.waitForIdle()
        tap(TestTags.Shared.CREATE)
        await(TestTags.ShareEntries.RESULT)
        compose.onNodeWithTag(TestTags.ShareEntries.RESULT, useUnmergedTree = true).assert(contains("1 added."))
        assertEquals(listOf(FakeSharingService.CREATE, FakeSharingService.ADD), h.sharing.kinds())
        assertEquals(CollectionRole.OWNER, h.rows.rows.single().role)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

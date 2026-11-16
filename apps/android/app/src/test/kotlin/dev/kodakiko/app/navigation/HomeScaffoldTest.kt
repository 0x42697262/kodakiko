package dev.kodakiko.app.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.kodakiko.app.RegisterComponentActivity
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.util.SystemInsetsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScaffoldTest {
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private val tabTags = listOf(TestTags.Tabs.GALLERY, TestTags.Tabs.CALENDAR, TestTags.Tabs.SHARED, TestTags.Tabs.MORE)

    private fun host(startScreen: StartScreen = StartScreen.GALLERY): ShellHost = ShellHost(compose, unlockedState(), startScreen)

    private fun tab(tag: String) = compose.onNodeWithTag(tag)

    private fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun showScrollingGallery(host: ShellHost, hideBarsOnScroll: Boolean) {
        host.show(homeSettings = HomeSettings(hideBarsOnScroll = hideBarsOnScroll, sharedTabVisible = true)) { route ->
            if (route == Route.Gallery) {
                LazyColumn(Modifier.fillMaxSize().testTag(GALLERY_LIST)) {
                    items(ROWS) { index ->
                        Text("row $index", modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT.dp))
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().testTag(routeTag(route)))
            }
        }
    }

    private fun scrollDown() {
        compose.onNodeWithTag(GALLERY_LIST).performTouchInput { swipeUp() }
        compose.waitForIdle()
    }

    private fun scrollUp() {
        compose.onNodeWithTag(GALLERY_LIST).performTouchInput { swipeDown() }
        compose.waitForIdle()
    }

    @Test
    fun theBottomBarShowsFourLabeledTabsInOrderWithTheV031Ids() {
        host().show()
        val lefts = tabTags.map { tag ->
            tab(tag).assertIsDisplayed()
            tab(tag).fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(lefts.sorted(), lefts)
        tab(TestTags.Tabs.GALLERY).assert(hasText(string(R.string.tabs_gallery)))
        tab(TestTags.Tabs.CALENDAR).assert(hasText(string(R.string.tabs_calendar)))
        tab(TestTags.Tabs.SHARED).assert(hasText(string(R.string.tabs_shared)))
        tab(TestTags.Tabs.MORE).assert(hasText(string(R.string.tabs_more)))
    }

    @Test
    fun theGalleryTabIsSelectedFirstByDefault() {
        val host = host().also { it.show() }
        tab(TestTags.Tabs.GALLERY).assertIsSelected()
        tab(TestTags.Tabs.CALENDAR).assertIsNotSelected()
        assertEquals(listOf(Route.Gallery), host.routes())
        compose.onNodeWithTag(routeTag(Route.Gallery)).assertIsDisplayed()
    }

    @Test
    fun tappingATabShowsItsScreenAndSelectsIt() {
        val host = host().also { it.show() }
        for ((tag, route) in listOf(
            TestTags.Tabs.CALENDAR to Route.Calendar,
            TestTags.Tabs.SHARED to Route.Shared,
            TestTags.Tabs.MORE to Route.More,
            TestTags.Tabs.GALLERY to Route.Gallery,
        )) {
            tab(tag).performClick()
            compose.waitForIdle()
            tab(tag).assertIsSelected()
            assertEquals(listOf(route), host.routes())
            compose.onNodeWithTag(routeTag(route)).assertIsDisplayed()
            tabTags.filter { it != tag }.forEach { tab(it).assertIsNotSelected() }
        }
    }

    @Test
    fun theStartScreenSettingChoosesTheFirstTab() {
        val host = host(StartScreen.CALENDAR).also { it.show() }
        tab(TestTags.Tabs.CALENDAR).assertIsSelected()
        tab(TestTags.Tabs.GALLERY).assertIsNotSelected()
        assertEquals(listOf(Route.Calendar), host.routes())
        compose.onNodeWithTag(routeTag(Route.Calendar)).assertIsDisplayed()
    }

    @Test
    fun backFromAnotherTabGoesToTheStartTabAndThenLeavesTheApp() {
        val host = host().also { it.show() }
        tab(TestTags.Tabs.MORE).performClick()
        compose.waitForIdle()
        assertTrue(host.backIsHandledInside())
        host.systemBack()
        assertEquals(listOf(Route.Gallery), host.routes())
        tab(TestTags.Tabs.GALLERY).assertIsSelected()
        assertFalse(host.backIsHandledInside())
    }

    @Test
    fun backFromTheGalleryGoesToACalendarStartTab() {
        val host = host(StartScreen.CALENDAR).also { it.show() }
        assertFalse(host.backIsHandledInside())
        tab(TestTags.Tabs.GALLERY).performClick()
        compose.waitForIdle()
        host.systemBack()
        assertEquals(listOf(Route.Calendar), host.routes())
        assertFalse(host.backIsHandledInside())
    }

    @Test
    fun theSharedTabIsHiddenWhenSharingIsUnavailable() {
        host().show(homeSettings = HomeSettings(hideBarsOnScroll = true, sharedTabVisible = false))
        assertFalse(exists(TestTags.Tabs.SHARED))
        tab(TestTags.Tabs.GALLERY).assertIsDisplayed()
        tab(TestTags.Tabs.CALENDAR).assertIsDisplayed()
        tab(TestTags.Tabs.MORE).assertIsDisplayed()
    }

    @Test
    fun aPushedScreenCoversTheBottomBar() {
        val host = host().also { it.show() }
        host.push(Route.Trash)
        assertTrue(tab(TestTags.Tabs.GALLERY).isNotShown())
        host.systemBack()
        tab(TestTags.Tabs.GALLERY).assertIsDisplayed()
    }

    @Test
    fun theBottomBarHidesWhileScrollingDownAndReturnsWhenScrollingUp() {
        val host = host()
        showScrollingGallery(host, hideBarsOnScroll = true)
        tab(TestTags.Tabs.GALLERY).assertIsDisplayed()
        scrollDown()
        assertTrue(tab(TestTags.Tabs.GALLERY).isNotShown())
        assertTrue(tab(TestTags.Tabs.MORE).isNotShown())
        scrollDown()
        assertTrue(tab(TestTags.Tabs.GALLERY).isNotShown())
        scrollUp()
        tab(TestTags.Tabs.GALLERY).assertIsDisplayed()
        tab(TestTags.Tabs.MORE).assertIsDisplayed()
    }

    @Test
    fun theBottomBarStaysWhenHideBarsOnScrollIsOff() {
        val host = host()
        showScrollingGallery(host, hideBarsOnScroll = false)
        scrollDown()
        tab(TestTags.Tabs.GALLERY).assertIsDisplayed()
        scrollDown()
        tab(TestTags.Tabs.GALLERY).assertIsDisplayed()
    }

    @Test
    fun theTabContentEndsAboveTheBottomBar() {
        host().show()
        val content = compose.onNodeWithTag(routeTag(Route.Gallery)).fetchSemanticsNode().boundsInRoot
        val bar = tab(TestTags.Tabs.GALLERY).fetchSemanticsNode().boundsInRoot
        assertTrue("content ${content.bottom} bar ${bar.top}", content.bottom <= bar.top)
    }

    @Test
    fun inFullScreenTheBottomBarKeepsTheMinimumBottomInset() {
        host().show(fullScreen = true)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val item = tab(TestTags.Tabs.GALLERY).fetchSemanticsNode().boundsInRoot
        val minimum = SystemInsetsConstants.FULL_SCREEN_MIN_BOTTOM.value * compose.density.density
        assertTrue("gap ${root.bottom - item.bottom} minimum $minimum", root.bottom - item.bottom >= minimum - 1f)
    }

    @Test
    fun outsideFullScreenTheBottomBarSitsOnTheRealInsetOnly() {
        host().show(fullScreen = false)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val item = tab(TestTags.Tabs.GALLERY).fetchSemanticsNode().boundsInRoot
        val minimum = SystemInsetsConstants.FULL_SCREEN_MIN_BOTTOM.value * compose.density.density
        assertTrue("gap ${root.bottom - item.bottom}", root.bottom - item.bottom < minimum)
    }

    private companion object {
        const val GALLERY_LIST = "test.galleryList"
        const val ROWS = 200
        const val ROW_HEIGHT = 48
    }
}

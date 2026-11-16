package dev.kodakiko.app.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import dev.kodakiko.app.RegisterComponentActivity
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppNavigationTest {
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private fun host(initial: AccountState, startScreen: StartScreen = StartScreen.GALLERY, entryExists: Boolean = true): ShellHost =
        ShellHost(compose, initial, startScreen, entryExists)

    private fun tabsShown(): Boolean = compose.onAllNodes(hasTestTag(TestTags.Tabs.GALLERY)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun noServerStartsAtWelcome() {
        val host = host(AccountState.NoServer).also { it.show() }
        assertEquals(listOf(Route.Welcome), host.routes())
        compose.onNodeWithTag(routeTag(Route.Welcome)).assertIsDisplayed()
        assertFalse(tabsShown())
    }

    @Test
    fun signedOutStartsAtWelcome() {
        val host = host(AccountState.SignedOut("alice")).also { it.show() }
        assertEquals(listOf(Route.Welcome), host.routes())
        assertFalse(tabsShown())
    }

    @Test
    fun lockedStartsAtTheLockScreen() {
        val host = host(AccountState.Locked).also { it.show() }
        assertEquals(listOf(Route.Lock), host.routes())
        compose.onNodeWithTag(routeTag(Route.Lock)).assertIsDisplayed()
        assertFalse(tabsShown())
    }

    @Test
    fun pendingDeletionStartsAtCancelDeletion() {
        val host = host(AccountState.PendingDeletion).also { it.show() }
        assertEquals(listOf(Route.CancelDeletion), host.routes())
        assertFalse(tabsShown())
    }

    @Test
    fun rendersTheTabsWhileUnlockedAndDoesNotNavigate() {
        val host = host(unlockedState()).also { it.show() }
        assertEquals(listOf(Route.Gallery), host.routes())
        compose.onNodeWithTag(TestTags.Tabs.GALLERY).assertIsDisplayed()
        compose.onNodeWithTag(routeTag(Route.Gallery)).assertIsDisplayed()
        assertNull(host.navigator.lastTransition)
    }

    private fun leavingUnlockedReplacesTheTabsWith(state: AccountState, expected: Route) {
        val host = host(unlockedState()).also { it.show() }
        host.setState(state)
        assertEquals(listOf(expected), host.routes())
        assertFalse(tabsShown())
        assertFalse(host.shown(Route.Gallery))
        compose.onNodeWithTag(routeTag(expected)).assertIsDisplayed()
    }

    @Test
    fun signingOutReplacesTheTabsWithWelcome() = leavingUnlockedReplacesTheTabsWith(AccountState.SignedOut(), Route.Welcome)

    @Test
    fun losingTheServerReplacesTheTabsWithWelcome() = leavingUnlockedReplacesTheTabsWith(AccountState.NoServer, Route.Welcome)

    @Test
    fun lockingReplacesTheTabsWithTheLockScreen() = leavingUnlockedReplacesTheTabsWith(AccountState.Locked, Route.Lock)

    @Test
    fun pendingDeletionReplacesTheTabsWithCancelDeletion() = leavingUnlockedReplacesTheTabsWith(AccountState.PendingDeletion, Route.CancelDeletion)

    @Test
    fun lockingReplacesTheWholeStackSoBackCannotRevealAProtectedScreen() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Entry("abc"))
        host.now += STEP_MS
        host.push(Route.EntryEdit("abc"))
        assertEquals(listOf(Route.Gallery, Route.Entry("abc"), Route.EntryEdit("abc")), host.routes())
        host.setState(AccountState.Locked)
        assertEquals(listOf(Route.Lock), host.routes())
        assertFalse(host.shown(Route.EntryEdit("abc")))
        assertFalse(host.backIsHandledInside())
        assertFalse(host.pop())
        assertEquals(listOf(Route.Lock), host.routes())
    }

    @Test
    fun aRouteThatNeedsUnlockCannotBeOpenedWhileLocked() {
        val host = host(AccountState.Locked).also { it.show() }
        host.push(Route.Trash)
        assertEquals(listOf(Route.Lock), host.routes())
        host.push(Route.Gallery)
        assertEquals(listOf(Route.Lock), host.routes())
        host.push(Route.Recover)
        assertEquals(listOf(Route.Lock, Route.Recover), host.routes())
    }

    @Test
    fun theOnboardingRoutesOpenBeforeUnlock() {
        val host = host(AccountState.NoServer).also { it.show() }
        host.push(Route.LocalSetup)
        assertEquals(listOf(Route.Welcome, Route.LocalSetup), host.routes())
        host.systemBack()
        host.push(Route.SignIn)
        assertEquals(listOf(Route.Welcome, Route.SignIn), host.routes())
        compose.onNodeWithTag(routeTag(Route.SignIn)).assertIsDisplayed()
    }

    @Test
    fun repeatedPushesOfTheSameRouteInsideTheLockWindowOpenItOnce() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Tags)
        repeat(9) {
            host.now += STEP_MS
            host.push(Route.Tags)
        }
        assertEquals(listOf(Route.Gallery, Route.Tags), host.routes())
        host.now += STEP_MS
        host.push(Route.Trash)
        assertEquals(listOf(Route.Gallery, Route.Tags, Route.Trash), host.routes())
    }

    @Test
    fun theSameRouteOpensAgainOnceTheWindowHasPassed() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Entry("abc"))
        host.now += NavigationConstants.NAVIGATION_LOCK_MS - 1
        host.push(Route.Entry("abc"))
        assertEquals(listOf(Route.Gallery, Route.Entry("abc")), host.routes())
        host.now += 1
        host.push(Route.Entry("abc"))
        assertEquals(listOf(Route.Gallery, Route.Entry("abc"), Route.Entry("abc")), host.routes())
    }

    @Test
    fun routesWithDifferentArgumentsAreDifferentPages() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Entry("abc"))
        host.now += STEP_MS
        host.push(Route.Entry("def"))
        assertEquals(listOf(Route.Gallery, Route.Entry("abc"), Route.Entry("def")), host.routes())
    }

    @Test
    fun backIsNeverDebounced() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Trash)
        host.now += STEP_MS
        host.push(Route.Tags)
        assertTrue(host.pop())
        assertTrue(host.pop())
        assertEquals(listOf(Route.Gallery), host.routes())
        assertFalse(host.pop())
        assertEquals(listOf(Route.Gallery), host.routes())
    }

    @Test
    fun systemBackPopsThePushedScreenAndTheRootStartTabLeavesBackToTheSystem() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Trash)
        compose.onNodeWithTag(routeTag(Route.Trash)).assertIsDisplayed()
        assertTrue(host.backIsHandledInside())
        host.systemBack()
        assertEquals(listOf(Route.Gallery), host.routes())
        assertFalse(host.shown(Route.Trash))
        compose.onNodeWithTag(routeTag(Route.Gallery)).assertIsDisplayed()
        assertFalse(host.backIsHandledInside())
    }

    @Test
    fun theRootOfTheSignedOutFlowLeavesBackToTheSystem() {
        val host = host(AccountState.SignedOut()).also { it.show() }
        assertFalse(host.backIsHandledInside())
        host.push(Route.SignIn)
        assertTrue(host.backIsHandledInside())
        host.systemBack()
        assertEquals(listOf(Route.Welcome), host.routes())
    }

    @Test
    fun replaceSwapsTheTopAndReplaceAllClearsTheStack() {
        val host = host(AccountState.SignedOut()).also { it.show() }
        host.push(Route.SignUp)
        compose.runOnIdle { host.navigator.replace(Route.Securing) }
        compose.waitForIdle()
        assertEquals(listOf(Route.Welcome, Route.Securing), host.routes())
        host.setState(unlockedState())
        compose.runOnIdle { host.navigator.replaceAll(Route.RecoveryKey) }
        compose.waitForIdle()
        assertEquals(listOf(Route.RecoveryKey), host.routes())
        compose.runOnIdle { host.navigator.replaceAll(Route.Home) }
        compose.waitForIdle()
        assertEquals(listOf(Route.Gallery), host.routes())
        compose.onNodeWithTag(TestTags.Tabs.GALLERY).assertIsDisplayed()
    }

    @Test
    fun theHomeAliasOpensTheStartTab() {
        val host = host(unlockedState(), startScreen = StartScreen.CALENDAR).also { it.show() }
        assertEquals(listOf(Route.Calendar), host.routes())
        host.push(Route.Trash)
        host.now += STEP_MS
        host.push(Route.Home)
        assertEquals(listOf(Route.Calendar), host.routes())
    }

    @Test
    fun pushingATabRouteReturnsToTheTabsWithThatTabSelected() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Day("2026-05-01"))
        host.now += STEP_MS
        host.push(Route.Calendar)
        assertEquals(listOf(Route.Calendar), host.routes())
        compose.onNodeWithTag(routeTag(Route.Calendar)).assertIsDisplayed()
        assertFalse(host.shown(Route.Day("2026-05-01")))
    }

    @Test
    fun pushesSlideUnlessTheyOpenCaptureOrThePlayer() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Entry("abc"))
        assertEquals(RouteTransition(TransitionKind.SLIDE, forward = true), host.navigator.lastTransition)
        host.now += STEP_MS
        host.push(Route.Play("abc", "m1"))
        assertEquals(RouteTransition(TransitionKind.FADE, forward = true), host.navigator.lastTransition)
        host.systemBack()
        assertEquals(RouteTransition(TransitionKind.FADE, forward = false), host.navigator.lastTransition)
        host.now += STEP_MS
        host.push(Route.Capture(entryId = "abc"))
        assertEquals(RouteTransition(TransitionKind.SLIDE_UP, forward = true), host.navigator.lastTransition)
        host.systemBack()
        assertEquals(RouteTransition(TransitionKind.SLIDE_UP, forward = false), host.navigator.lastTransition)
        host.systemBack()
        assertEquals(RouteTransition(TransitionKind.SLIDE, forward = false), host.navigator.lastTransition)
        assertEquals(listOf(Route.Gallery), host.routes())
    }

    @Test
    fun screensNavigateThroughTheRouteNavigatorOnAVoyagerStack() {
        val host = host(unlockedState())
        val seenNavigators = mutableListOf<RouteNavigator>()
        val voyagerDepths = mutableMapOf<Route, Int>()
        val tabs = mutableListOf<Route>()
        val voyagerTabs = mutableListOf<String>()
        host.show { route ->
            seenNavigators += LocalRouteNavigator.current
            if (route is Route.Tab) {
                voyagerTabs += LocalTabNavigator.current.current.key
                tabs += route
            } else {
                voyagerDepths[route] = LocalNavigator.current?.items?.size ?: -1
            }
            Probe(route)
        }
        host.push(Route.Trash)
        assertTrue(seenNavigators.isNotEmpty())
        seenNavigators.forEach { assertSame(host.navigator, it) }
        assertTrue(tabs.contains(Route.Gallery))
        assertTrue(voyagerTabs.isNotEmpty())
        assertEquals(2, voyagerDepths[Route.Trash])
    }

    @Test
    fun aRememberedRouteIsRestoredAfterUnlock() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Day("2026-05-01"))
        host.setState(AccountState.Locked)
        assertEquals(listOf(Route.Lock), host.routes())
        assertEquals("/day/2026-05-01", host.returnTo.value)
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Gallery, Route.Day("2026-05-01")))
        assertNull(host.returnTo.value)
        compose.onNodeWithTag(routeTag(Route.Day("2026-05-01"))).assertIsDisplayed()
    }

    @Test
    fun aRememberedTabIsRestoredAfterUnlock() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Calendar)
        host.setState(AccountState.Locked)
        assertEquals("/calendar", host.returnTo.value)
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Calendar))
    }

    @Test
    fun theGalleryTabIsRememberedAsTheRootPathAndRestoredEvenWithAnotherStartScreen() {
        val host = host(unlockedState(), startScreen = StartScreen.CALENDAR).also { it.show() }
        host.push(Route.Gallery)
        host.setState(AccountState.Locked)
        assertEquals("/", host.returnTo.value)
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Gallery))
    }

    @Test
    fun aRememberedSettingsScreenIsRestoredAfterUnlock() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.SettingsAppearance)
        host.setState(AccountState.Locked)
        assertEquals("/settings/appearance", host.returnTo.value)
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Gallery, Route.SettingsAppearance))
    }

    @Test
    fun returnsToAnEntryWhenItStillExists() {
        val host = host(unlockedState(), entryExists = true).also { it.show() }
        host.push(Route.Entry("abc"))
        host.setState(AccountState.Locked)
        assertEquals("/entry/abc", host.returnTo.value)
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Gallery, Route.Entry("abc")))
        assertEquals(listOf("abc"), host.askedEntries)
    }

    @Test
    fun goesHomeWhenTheRememberedEntryIsGone() {
        val host = host(unlockedState(), entryExists = false).also { it.show() }
        host.push(Route.Entry("abc"))
        host.setState(AccountState.Locked)
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Gallery))
        assertEquals(listOf("abc"), host.askedEntries)
        assertNull(host.returnTo.value)
    }

    @Test
    fun goesHomeWhenTheEditedEntryIsGone() {
        val host = host(unlockedState(), entryExists = false).also { it.show() }
        host.push(Route.EntryEdit("abc"))
        host.setState(AccountState.Locked)
        assertEquals("/entry/abc/edit", host.returnTo.value)
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Gallery))
    }

    @Test
    fun goesToTheStartTabWithoutAReturnPath() {
        val host = host(AccountState.Locked).also { it.show() }
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Gallery))
        assertEquals(emptyList<String>(), host.askedEntries)
    }

    @Test
    fun goesToTheChosenStartTabWithoutAReturnPath() {
        val host = host(AccountState.Locked, startScreen = StartScreen.CALENDAR).also { it.show() }
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Calendar))
        compose.onNodeWithTag(TestTags.Tabs.CALENDAR).assertIsDisplayed()
    }

    private fun neverReturnsTo(stale: String) {
        val host = host(AccountState.Locked).also { it.show() }
        host.returnTo.value = stale
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Gallery))
        assertNull(host.returnTo.value)
    }

    @Test
    fun neverReturnsToTheLockScreen() = neverReturnsTo("/lock")

    @Test
    fun neverReturnsToSignIn() = neverReturnsTo("/sign-in")

    @Test
    fun leavingTheLockedStateForAnythingButUnlockForgetsTheReturnPath() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Trash)
        host.setState(AccountState.Locked)
        assertEquals("/trash", host.returnTo.value)
        host.setState(AccountState.SignedOut("alice"))
        assertNull(host.returnTo.value)
        assertEquals(listOf(Route.Welcome), host.routes())
    }

    @Test
    fun signingOutWhileUnlockedRemembersNothing() {
        val host = host(unlockedState()).also { it.show() }
        host.push(Route.Trash)
        host.setState(AccountState.SignedOut())
        assertNull(host.returnTo.value)
        assertEquals(listOf(Route.Welcome), host.routes())
    }

    @Composable
    private fun Probe(route: Route) {
        Box(Modifier.fillMaxSize().testTag(routeTag(route)))
    }

    private companion object {
        const val STEP_MS = 30L
    }
}

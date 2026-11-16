package dev.kodakiko.app.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.kodakiko.app.RegisterComponentActivity
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.feature.auth.SecuringScreen
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecuringOverlayTest {
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private val events = Channel<Route>(Channel.UNLIMITED)

    private val composed = mutableMapOf<Route, Int>()

    private val disposed = mutableMapOf<Route, Int>()

    @Composable
    private fun FormThatReplacesAll(route: Route) {
        val navigator = LocalRouteNavigator.current
        DisposableEffect(route) {
            composed[route] = (composed[route] ?: 0) + 1
            onDispose { disposed[route] = (disposed[route] ?: 0) + 1 }
        }
        LaunchedEffect(navigator) {
            for (target in events) navigator.replaceAll(target)
        }
        Box(Modifier.fillMaxSize().testTag(routeTag(route)))
    }

    private fun host(initial: AccountState = AccountState.SignedOut()): ShellHost {
        val host = ShellHost(compose, initial)
        host.show { route ->
            when (route) {
                Route.SignUp, Route.LocalSetup -> FormThatReplacesAll(route)
                Route.Securing -> SecuringScreen()
                else -> Box(Modifier.fillMaxSize().testTag(routeTag(route)))
            }
        }
        return host
    }

    private fun securingShown(): Boolean = compose.onAllNodes(hasTestTag(TestTags.SignUp.SECURING_SCREEN)).fetchSemanticsNodes().isNotEmpty()

    private fun showSecuring(host: ShellHost) {
        compose.runOnIdle { host.navigator.showSecuring() }
        compose.waitForIdle()
    }

    private fun send(route: Route) {
        compose.runOnIdle { events.trySend(route) }
        compose.waitForIdle()
    }

    private fun openSignUpAndSecure(): ShellHost {
        val host = host()
        host.push(Route.SignUp)
        showSecuring(host)
        return host
    }

    @Test
    fun theOverlayDrawsOverSignUpWithoutPushingARouteOrDisposingSignUp() {
        val host = openSignUpAndSecure()
        assertTrue(securingShown())
        compose.onNodeWithTag(TestTags.SignUp.SECURING).assertIsDisplayed()
        assertEquals(listOf(Route.Welcome, Route.SignUp), host.routes())
        assertTrue(host.shown(Route.SignUp))
        assertEquals(1, composed[Route.SignUp])
        assertEquals(null, disposed[Route.SignUp])
        assertTrue(compose.runOnIdle { host.navigator.securing })
    }

    @Test
    fun signUpsReplaceAllToTheRecoveryKeyLandsAndClearsTheOverlay() {
        val host = openSignUpAndSecure()
        host.setState(unlockedState())
        assertTrue(securingShown())
        assertTrue(host.shown(Route.SignUp))
        send(Route.RecoveryKey)
        assertEquals(listOf<Route>(Route.RecoveryKey), host.routes())
        assertFalse(securingShown())
        assertFalse(compose.runOnIdle { host.navigator.securing })
        assertTrue(host.shown(Route.RecoveryKey))
        assertEquals(1, composed[Route.SignUp])
    }

    @Test
    fun localSetupStaysComposedUnderTheOverlayAndItsEventLands() {
        val host = host(AccountState.NoServer)
        host.push(Route.LocalSetup)
        showSecuring(host)
        assertTrue(securingShown())
        assertEquals(listOf(Route.Welcome, Route.LocalSetup), host.routes())
        assertTrue(host.shown(Route.LocalSetup))
        host.setState(unlockedState())
        send(Route.RecoveryKey)
        assertEquals(listOf<Route>(Route.RecoveryKey), host.routes())
        assertFalse(securingShown())
        assertEquals(1, composed[Route.LocalSetup])
    }

    @Test
    fun systemBackWhileSecuringIsSwallowedAndSignUpStays() {
        val host = openSignUpAndSecure()
        assertTrue(host.backIsHandledInside())
        host.systemBack()
        assertEquals(listOf(Route.Welcome, Route.SignUp), host.routes())
        assertTrue(securingShown())
        assertEquals(null, disposed[Route.SignUp])
    }

    @Test
    fun dismissingTheOverlayAfterAFailureLeavesSignUpAsItWas() {
        val host = openSignUpAndSecure()
        compose.runOnIdle { host.navigator.dismissSecuring() }
        compose.waitForIdle()
        assertFalse(securingShown())
        assertEquals(listOf(Route.Welcome, Route.SignUp), host.routes())
        assertTrue(host.shown(Route.SignUp))
        assertEquals(1, composed[Route.SignUp])
        assertEquals(null, disposed[Route.SignUp])
        host.systemBack()
        assertEquals(listOf<Route>(Route.Welcome), host.routes())
    }

    @Test
    fun aReplaceClearsTheOverlay() {
        val host = openSignUpAndSecure()
        compose.runOnIdle { host.navigator.replace(Route.SignIn) }
        compose.waitForIdle()
        assertFalse(securingShown())
        assertEquals(listOf(Route.Welcome, Route.SignIn), host.routes())
    }

    @Test
    fun aStateReRouteClearsTheOverlay() {
        val host = openSignUpAndSecure()
        host.setState(AccountState.PendingDeletion)
        assertFalse(securingShown())
        assertEquals(listOf<Route>(Route.CancelDeletion), host.routes())
        assertEquals(1, disposed[Route.SignUp])
    }

    @Test
    fun unlockingAfterALockClearsTheOverlay() {
        val host = openSignUpAndSecure()
        host.setState(AccountState.Locked)
        assertFalse(securingShown())
        assertEquals(listOf<Route>(Route.Lock), host.routes())
        showSecuring(host)
        host.setState(unlockedState())
        host.awaitRoutes(listOf(Route.Gallery))
        assertFalse(securingShown())
    }

    @Test
    fun theOverlayCanShowAgainAfterItCleared() {
        val host = openSignUpAndSecure()
        compose.runOnIdle { host.navigator.dismissSecuring() }
        compose.waitForIdle()
        showSecuring(host)
        assertTrue(securingShown())
        assertTrue(host.shown(Route.SignUp))
        assertEquals(1, composed[Route.SignUp])
    }
}

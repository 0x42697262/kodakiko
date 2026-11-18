package dev.kodakiko.app.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.kodakiko.app.RegisterComponentActivity
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class ChangeWhenRemembered(private val change: () -> Unit) : RememberObserver {
    override fun onRemembered() = change()

    override fun onForgotten() = Unit

    override fun onAbandoned() = Unit
}

@RunWith(RobolectricTestRunner::class)
class StateChangeBeforeBindTest {
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private fun routesAfterAStartupChange(initial: AccountState, next: AccountState): List<Route> {
        val state = Observable(initial)
        val navigator = AppNavigator(
            accountState = state,
            returnTo = Observable<String?>(null),
            entryExists = { false },
            startScreen = { StartScreen.GALLERY },
            clock = { 1_000L },
        )
        compose.setContent {
            KodakikoTheme {
                remember { ChangeWhenRemembered { state.value = next } }
                AppNavigation(navigator = navigator, homeSettings = HomeSettings(hideBarsOnScroll = true, sharedTabVisible = true)) { route ->
                    Box(Modifier.fillMaxSize().testTag(routeTag(route)))
                }
            }
        }
        compose.waitForIdle()
        return compose.runOnIdle { navigator.routes }
    }

    @Test
    fun anAccountRestoredAsLockedWhileTheShellStartsShowsTheLockScreen() {
        assertEquals(listOf(Route.Lock), routesAfterAStartupChange(AccountState.NoServer, AccountState.Locked))
    }

    @Test
    fun aPendingDeletionFoundWhileTheShellStartsShowsCancelDeletion() {
        assertEquals(listOf(Route.CancelDeletion), routesAfterAStartupChange(AccountState.NoServer, AccountState.PendingDeletion))
    }

    @Test
    fun aSignOutWhileTheShellStartsShowsWelcome() {
        assertEquals(listOf(Route.Welcome), routesAfterAStartupChange(AccountState.Locked, AccountState.SignedOut()))
    }
}

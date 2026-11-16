package dev.kodakiko.app.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.account.AccountKeys
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.theme.KodakikoTheme
import dev.kodakiko.ui.core.util.LocalFullScreen

fun unlockedState(): AccountState =
    AccountState.Unlocked(keys = AccountKeys(ByteArray(KEY_BYTES), ByteArray(KEY_BYTES), ByteArray(KEY_BYTES)), userId = "user-1", username = "alice")

private const val KEY_BYTES = 32

fun routeTag(route: Route): String = "route:${route.path}"

fun string(id: Int, vararg args: Any): String =
    ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

fun SemanticsNodeInteraction.isNotShown(): Boolean = runCatching { assertIsDisplayed() }.isFailure

class ShellHost(
    private val compose: AndroidComposeTestRule<*, ComponentActivity>,
    initial: AccountState,
    var startScreen: StartScreen = StartScreen.GALLERY,
    var entryExists: Boolean = true,
) {
    val accountState = Observable(initial)

    val returnTo = Observable<String?>(null)

    var now = START_TIME_MS

    val askedEntries = mutableListOf<String>()

    val navigator = AppNavigator(
        accountState = accountState,
        returnTo = returnTo,
        entryExists = { id ->
            askedEntries += id
            entryExists
        },
        startScreen = { startScreen },
        clock = { now },
    )

    fun show(
        homeSettings: HomeSettings = HomeSettings(hideBarsOnScroll = true, sharedTabVisible = true),
        fullScreen: Boolean = false,
        content: @Composable (Route) -> Unit = { route -> Box(Modifier.fillMaxSize().testTag(routeTag(route))) },
    ) {
        compose.setContent {
            KodakikoTheme {
                CompositionLocalProvider(LocalFullScreen provides fullScreen) {
                    AppNavigation(navigator = navigator, homeSettings = homeSettings, content = content)
                }
            }
        }
        compose.waitForIdle()
    }

    fun routes(): List<Route> = compose.runOnIdle { navigator.routes }

    fun push(route: Route) {
        compose.runOnIdle { navigator.push(route) }
        compose.waitForIdle()
    }

    fun pop(): Boolean {
        val popped = compose.runOnIdle { navigator.pop() }
        compose.waitForIdle()
        return popped
    }

    fun setState(state: AccountState) {
        compose.runOnIdle { accountState.value = state }
        compose.waitForIdle()
    }

    fun systemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    fun backIsHandledInside(): Boolean = compose.runOnIdle { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() }

    fun awaitRoutes(expected: List<Route>) {
        compose.waitUntil(WAIT_MS) { compose.runOnIdle { navigator.routes } == expected }
        compose.waitForIdle()
    }

    fun shown(route: Route): Boolean = compose.onAllNodes(hasTestTag(routeTag(route))).fetchSemanticsNodes().isNotEmpty()

    companion object {
        const val START_TIME_MS = 1_000L
        const val WAIT_MS = 5_000L
    }
}

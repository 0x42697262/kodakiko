package dev.kodakiko.ui.feature.lock

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LockScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val auth = FakeAuthService()

    private class RecordingNavigator : RouteNavigator {
        val log = mutableListOf<String>()

        override val routes: List<Route> get() = listOf(Route.Lock)

        override fun push(route: Route) {
            log += "push ${route.path}"
        }

        override fun pop(): Boolean = false

        override fun replace(route: Route) {
            log += "replace ${route.path}"
        }

        override fun replaceAll(route: Route) {
            log += "replaceAll ${route.path}"
        }
    }

    private val navigator = RecordingNavigator()

    private fun showScreen(locksInSession: Int = 0) {
        val viewModel = LockViewModel(
            auth = auth,
            lock = lockControl(locksInSession),
            adoption = Fixture.emptyAdoption(),
            pendingChanges = PendingChanges { 0 },
            ioDispatcher = Dispatchers.IO,
        )
        compose.show {
            CompositionLocalProvider(LocalRouteNavigator provides navigator) { LockScreen(viewModel = viewModel) }
        }
    }

    @Test
    fun forgotOpensRecovery() {
        showScreen()
        compose.onNodeWithTag(TestTags.Lock.FORGOT).performScrollTo().performClick()
        compose.waitUntil(WAIT_MS) { navigator.log.isNotEmpty() }
        assertEquals(listOf("push /recover"), navigator.log)
    }

    @Test
    fun theU05PasswordPathUnlocksWithTheTypedPassword() {
        showScreen()
        compose.waitUntil(WAIT_MS) { compose.exists(TestTags.Lock.LOCKED_AS) }
        compose.onNodeWithTag(TestTags.Lock.UNLOCK_PASSWORD).performScrollTo().performClick()
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().performClick().performTextInput(GOOD_PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performImeAction()
        compose.waitUntil(WAIT_MS) { auth.count("unlockWithPassword") == 1 }
        assertEquals(listOf<Any?>(GOOD_PASSWORD), auth.args("unlockWithPassword"))
    }

    @Test
    fun aLockInTheSessionPromptsOnceAcrossRecompositions() {
        showScreen(locksInSession = 1)
        compose.waitUntil(WAIT_MS) { compose.exists(TestTags.Lock.PASSWORD) }
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().performTextInput("abc")
        compose.waitForIdle()
        assertEquals(1, auth.count("unlockWithDevice"))
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

package dev.kodakiko.app.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.app.FakeAndroidKeyStore
import dev.kodakiko.app.KodakikoApp
import dev.kodakiko.app.MainActivity
import dev.kodakiko.app.ServerFixture
import dev.kodakiko.app.V031Fixture
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import org.junit.After
import org.junit.Rule

abstract class AppWiringHarness {
    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    val app: KodakikoApp get() = ApplicationProvider.getApplicationContext()

    var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDownApp() {
        scenario?.close()
        ServerFixture.shutdown()
        FakeAndroidKeyStore.uninstall()
    }

    fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun awaitTag(tag: String, timeoutMs: Long = WAIT_MS) {
        compose.waitUntil(timeoutMs) { exists(tag) }
    }

    fun awaitGone(tag: String, timeoutMs: Long = WAIT_MS) {
        compose.waitUntil(timeoutMs) { !exists(tag) }
    }

    fun click(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    fun tap(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    fun back() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    fun push(route: Route) {
        scenario!!.onActivity { app.graph.navigator.push(route) }
        compose.waitForIdle()
    }

    fun contains(text: String): SemanticsMatcher = hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))

    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    fun enterPassword() {
        compose.waitUntil(UNLOCK_WAIT_MS) { exists(TestTags.Lock.PASSWORD) || exists(TestTags.Lock.UNLOCK_PASSWORD) }
        if (!exists(TestTags.Lock.PASSWORD)) tap(TestTags.Lock.UNLOCK_PASSWORD)
        awaitTag(TestTags.Lock.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().performClick().performTextInput(V031Fixture.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performImeAction()
    }

    fun unlockToGallery() {
        launch()
        enterPassword()
        awaitTag(listItem(TestTags.Gallery.TILE, 0), UNLOCK_WAIT_MS)
    }

    fun lockFromGallery() {
        tap(TestTags.Gallery.MORE)
        tap(TestTags.Lock.LOCK_NOW)
        awaitTag(TestTags.Lock.SCREEN, UNLOCK_WAIT_MS)
    }

    fun pullToSync(tag: String = TestTags.PULL_TO_SYNC) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performTouchInput {
            val start = Offset(centerX, centerY)
            swipe(start = start, end = start + Offset(0f, PULL_DISTANCE), durationMillis = PULL_MS)
        }
        compose.waitForIdle()
    }

    companion object {
        const val WAIT_MS = 15_000L
        const val SYNC_WAIT_MS = 30_000L
        const val UNLOCK_WAIT_MS = 120_000L
        const val PULL_DISTANCE = 400f
        const val PULL_MS = 600L
    }
}

package dev.kodakiko.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.GlobalStore
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.feature.lock.BiometricDeviceGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.kodakiko.app.TestTags as AppTestTags

@RunWith(RobolectricTestRunner::class)
class AppStartTest {
    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val app: KodakikoApp get() = ApplicationProvider.getApplicationContext()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        FakeAndroidKeyStore.uninstall()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitTag(tag: String, timeoutMs: Long = WAIT_MS) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMs)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitGone(tag: String, timeoutMs: Long = WAIT_MS) {
        compose.waitUntilDoesNotExist(hasTestTag(tag), timeoutMs)
    }

    private fun shown(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theApplicationIsKodakikoAppAndMainActivityCanHostTheBiometricPrompt() {
        assertTrue(context is KodakikoApp)
        launch()
        scenario!!.onActivity { assertTrue(it is FragmentActivity) }
    }

    @Test
    fun theGraphHoldsOneOfEachServiceAndTheBiometricGate() {
        val graph = app.graph
        assertSame(graph, app.graph)
        assertSame(graph.authService, graph.authService)
        assertSame(graph.accountState, graph.accountState)
        assertSame(graph.lockControl, graph.lockControl)
        assertSame(graph.legacyAdoption, graph.legacyAdoption)
        assertTrue(graph.deviceGate is BiometricDeviceGate)
    }

    @Test
    fun anEmptyDeviceStartsAtWelcomeWithAnEmptyServerField() {
        launch()
        awaitTag(TestTags.Welcome.SCREEN)
        compose.onNodeWithTag(TestTags.Welcome.TITLE).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Welcome.SERVER_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(AccountState.NoServer, app.graph.accountState.value)
        assertFalse(shown(TestTags.Lock.TITLE))
        assertFalse(shown(TestTags.Tabs.GALLERY))
    }

    @Test
    fun useWithoutAServerOpensTheRealLocalSetupScreen() {
        launch()
        awaitTag(TestTags.Welcome.LOCAL)
        compose.onNodeWithTag(TestTags.Welcome.LOCAL).performClick()
        awaitTag(TestTags.LocalSetup.SCREEN)
        compose.onNodeWithTag(TestTags.LocalSetup.UNDERSTAND).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(TestTags.LocalSetup.CREATE).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(application = LockedLocalAccountApp::class)
    fun aLockedAccountStartsAtTheLockScreen() {
        launch()
        awaitTag(TestTags.Lock.TITLE)
        compose.onNodeWithTag(TestTags.Lock.TITLE).assertTextEquals("Journal Locked")
        awaitTag(TestTags.Lock.LOCKED_AS)
        compose.onNodeWithTag(TestTags.Lock.LOCKED_AS).assertTextEquals("Locked — alice")
        assertEquals(AccountState.Locked, app.graph.accountState.value)
        awaitGone(TestTags.Welcome.SCREEN)
        assertFalse(shown(TestTags.Lock.UPDATED))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theFirstStartAfterTheUpdateAdoptsTheV031JournalAndUnlocksWithItsPassword() {
        launch()
        awaitTag(TestTags.Lock.TITLE)
        awaitTag(TestTags.Lock.UPDATED)
        awaitGone(TestTags.Welcome.SCREEN)
        compose.onNodeWithTag(TestTags.Lock.UPDATED).assertTextEquals("Kodakiko was updated. Enter your password to unlock.")
        compose.onNodeWithTag(TestTags.Lock.LOCKED_AS).assertTextEquals("Locked — ${V031Fixture.USERNAME}")
        assertEquals(
            ActiveAccount(ActiveAccount.CURRENT_SCHEMA_VERSION, V031Fixture.ACCOUNT_KEY, V031Fixture.USERNAME, AccountMode.LOCAL),
            GlobalStore(context).activeAccount(),
        )
        assertTrue(V031Fixture.legacyItemNames(context).isEmpty())
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().performClick().performTextInput(V031Fixture.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performImeAction()
        awaitTag(TestTags.Home.SIGNED_IN_AS, UNLOCK_WAIT_MS)
        compose.onNodeWithTag(TestTags.Home.SIGNED_IN_AS).assertTextEquals("Signed in as ${V031Fixture.USERNAME}")
        assertTrue(shown(AppTestTags.APP_PLACEHOLDER))
        assertTrue(app.graph.accountState.value is AccountState.Unlocked)
        awaitGone(TestTags.Lock.TITLE)
    }

    @Test
    @Config(application = CorruptV031App::class)
    fun aCorruptV031JournalIsReportedAndLeftUntouched() {
        launch()
        awaitTag(TestTags.Lock.UNREADABLE)
        compose.onNodeWithTag(TestTags.Lock.UNREADABLE).assertTextEquals("Your journal could not be opened.")
        assertFalse(shown(TestTags.Lock.UNLOCK_PASSWORD))
        awaitGone(TestTags.Welcome.SCREEN)
        val file = V031Fixture.databaseFile(context)
        assertTrue(file.isFile)
        val seeded = CorruptV031App.seededSha256
        assertNotNull(seeded)
        assertEquals(seeded, V031Fixture.sha256(file))
        assertTrue(V031Fixture.legacyItemNames(context).isNotEmpty())
        assertNull(GlobalStore(context).activeAccount())
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val UNLOCK_WAIT_MS = 120_000L
    }
}

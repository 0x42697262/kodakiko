@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.auth

import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.ServerChoice
import dev.kodakiko.service.account.ServerChoiceFailure
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator
import dev.kodakiko.ui.core.theme.KodakikoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

val RECOVERY_WORDS: List<String> = listOf(
    "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract",
    "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid",
    "acoustic", "acquire", "across", "act", "action", "actor", "actress", "actual",
)

const val TOKEN = "kdk_i_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
const val INVITE_LINK = "https://journal.example.test/invite#$TOKEN"
const val BASE_URL = "https://journal.example.test"
const val GOOD_PASSWORD = "correct horse battery staple"
const val OTHER_PASSWORD = "another long passphrase here"

val resources: Resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

fun string(@StringRes id: Int, vararg args: Any): String =
    ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

fun publicConfig(signupMode: SignupMode = SignupMode.INVITE): PublicConfigDto =
    PublicConfigDto(
        signupMode = signupMode,
        sharingEnabled = false,
        publicLinksEnabled = false,
        maxMediaPerEntry = 20,
        maxUploadBytes = 1000,
        defaultQuotaBytes = 0,
        trashDays = 30,
        accountDeletionGraceDays = 7,
        serverVersion = "0.1.0",
    )

class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class Observed<S, E>(private val state: StateFlow<S>, val events: List<E>) {
    val value: S get() = state.value
}

fun <S, E> TestScope.observe(state: StateFlow<S>, events: Flow<E>): Observed<S, E> {
    val received = mutableListOf<E>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { events.collect { received += it } }
    return Observed(state, received)
}

data class Call(val name: String, val args: List<Any?>)

class FakeAuthService : AuthService {
    override val mode: Observable<AccountMode> = Observable(AccountMode.SERVER)

    val calls = mutableListOf<Call>()
    val failures = mutableMapOf<String, Throwable>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    var choice: ServerChoice = ServerChoice.Failed(ServerChoiceFailure.UNREACHABLE)
    var pendingWords: List<String>? = RECOVERY_WORDS
    var deviceUnlocks = false
    var username: String? = null
    var deletionNotice: String? = null

    fun count(name: String): Int = calls.count { it.name == name }

    fun args(name: String): List<Any?> = calls.last { it.name == name }.args

    fun hold(name: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[name] = it }

    private suspend fun <T> record(name: String, vararg args: Any?, result: () -> T): T {
        calls += Call(name, args.toList())
        gates[name]?.await()
        failures[name]?.let { throw it }
        return result()
    }

    override suspend fun restore() = record("restore") {}

    override suspend fun chooseServer(input: String): ServerChoice = record("chooseServer", input) { choice }

    override suspend fun signUp(username: String, password: String, inviteToken: String?): List<String> =
        record("signUp", username, password, inviteToken) { RECOVERY_WORDS }

    override suspend fun confirmRecoveryKeySaved() = record("confirmRecoveryKeySaved") {}

    override suspend fun signIn(username: String, password: String) = record("signIn", username, password) {}

    override suspend fun unlockWithPassword(password: String) = record("unlockWithPassword", password) {}

    override suspend fun unlockWithDevice(): Boolean = record("unlockWithDevice") { deviceUnlocks }

    override suspend fun recoverAccount(username: String, words: String, newPassword: String) =
        record("recoverAccount", username, words, newPassword) {}

    override suspend fun pendingRecoveryWords(): List<String>? = record("pendingRecoveryWords") { pendingWords }

    override suspend fun recoveryPending(): Boolean = record("recoveryPending") { pendingWords != null }

    override suspend fun cancelDeletion() = record("cancelDeletion") {}

    override suspend fun signOut() = record("signOut") {}

    override suspend fun activeUsername(): String? = record("activeUsername") { username }

    override suspend fun changePassword(currentPassword: String, newPassword: String) = record("changePassword", currentPassword, newPassword) {}

    override suspend fun revealRecoveryWords(password: String): List<String> = record("revealRecoveryWords", password) { RECOVERY_WORDS }

    override suspend fun verifyPassword(password: String): Boolean = record("verifyPassword", password) { true }

    override suspend fun requestAccountDeletion(): String = record("requestAccountDeletion") { "2026-11-21T10:00:00.000Z" }

    override fun deletionScheduledFor(): String? = deletionNotice

    override suspend fun changeServer() = record("changeServer") {}

    override suspend fun setUpLocal(username: String, password: String): List<String> =
        record("setUpLocal", username, password) { RECOVERY_WORDS }

    override suspend fun connectServer(serverInput: String, inviteToken: String?, username: String, password: String) =
        record("connectServer", serverInput, inviteToken, username, password) {}

    override suspend fun eraseLocal() = record("eraseLocal") {}
}

class FakeConfigService(var config: PublicConfigDto?) : ConfigService {
    override fun current(): PublicConfigDto? = config

    override suspend fun refresh(): PublicConfigDto? = config

    override fun accept(config: PublicConfigDto) {
        this.config = config
    }

    override fun subscribe(listener: (PublicConfigDto) -> Unit): () -> Unit = {}
}

class FakeNavigator(initial: List<Route>) : RouteNavigator {
    private val stack = initial.toMutableList()

    val log = mutableListOf<String>()

    override val routes: List<Route> get() = stack.toList()

    override fun push(route: Route) {
        log += "push ${route.path}"
        stack += route
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
        stack += route
    }
}

fun AndroidComposeTestRule<*, ComponentActivity>.show(appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
    setContent { KodakikoTheme(appTheme = appTheme) { content() } }
    waitForIdle()
}

fun AndroidComposeTestRule<*, *>.exists(tag: String): Boolean = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

fun hasEditableText(text: String): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

val isPassword: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

fun SemanticsNodeInteraction.isNotShown(): Boolean = runCatching { assertIsDisplayed() }.isFailure

object Screenshots {
    const val QUALIFIERS = "w360dp-h640dp-xhdpi"

    private const val DIRECTORY = "src/test/screenshots"

    fun path(name: String): String = "$DIRECTORY/$name.png"

    fun options(name: String): RoborazziOptions {
        val requested = roborazziSystemPropertyTaskType()
        val taskType = when {
            requested.isEnabled() -> requested
            File(path(name)).isFile -> RoborazziTaskType.Verify
            else -> RoborazziTaskType.Record
        }
        return RoborazziOptions(taskType = taskType)
    }
}

fun SemanticsNodeInteraction.captureScreenshot(name: String) {
    captureRoboImage(Screenshots.path(name), Screenshots.options(name))
}

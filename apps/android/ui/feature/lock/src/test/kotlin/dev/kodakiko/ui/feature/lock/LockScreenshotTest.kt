package dev.kodakiko.ui.feature.lock

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.ui.core.format.errorBanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class LockScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showLock(state: LockState, appTheme: AppTheme = AppTheme.LIGHT) {
        compose.show(appTheme) {
            LockContent(
                state = state,
                onUnlockWithDevice = {},
                onUnlockWithPassword = {},
                onPasswordChange = {},
                onForgot = {},
                onSignOut = {},
                onSignOutConfirm = {},
                onDismissDialog = {},
            )
        }
    }

    private fun capture(name: String, state: LockState, appTheme: AppTheme = AppTheme.LIGHT) {
        showLock(state, appTheme)
        compose.onRoot().captureScreenshot("lock/$name")
    }

    @Test
    fun locked() = capture("locked", LockState(username = "alice"))

    @Test
    fun lockedDark() = capture("locked_dark", LockState(username = "alice"), AppTheme.DARK)

    @Test
    fun busy() = capture("busy", LockState(username = "alice", busy = true))

    @Test
    fun passwordShown() = capture("password_shown", LockState(username = "alice", passwordShown = true, password = GOOD_PASSWORD))

    @Test
    fun wrongPassword() = capture("wrong_password", LockState(username = "alice", passwordShown = true, wrongPassword = true))

    @Test
    fun errorBannerShown() = capture(
        "error_banner",
        LockState(username = "alice", passwordShown = true, error = errorBanner(AuthError(AuthFailure.INVALID_CREDENTIALS))),
    )

    @Test
    fun updatedNotice() = capture("updated_notice", LockState(username = "k007local", passwordShown = true, updatedNotice = true))

    @Test
    fun unreadable() = capture("unreadable", LockState(username = "k007local", unreadable = true))

    @Test
    fun signOutDialog() {
        showLock(LockState(username = "alice", dialog = LockDialog.SignOut))
        captureScreenRoboImage(Screenshots.path("lock/sign_out_dialog"), Screenshots.options("lock/sign_out_dialog"))
    }
}

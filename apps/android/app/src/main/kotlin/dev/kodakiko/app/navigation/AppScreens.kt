package dev.kodakiko.app.navigation

import androidx.compose.runtime.Composable
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.feature.auth.CancelDeletionScreen
import dev.kodakiko.ui.feature.auth.ConnectServerScreen
import dev.kodakiko.ui.feature.auth.LocalSetupScreen
import dev.kodakiko.ui.feature.auth.RecoverScreen
import dev.kodakiko.ui.feature.auth.RecoveryKeyScreen
import dev.kodakiko.ui.feature.auth.SecuringScreen
import dev.kodakiko.ui.feature.auth.SignInScreen
import dev.kodakiko.ui.feature.auth.SignUpScreen
import dev.kodakiko.ui.feature.auth.WelcomeScreen
import dev.kodakiko.ui.feature.calendar.CalendarScreen
import dev.kodakiko.ui.feature.gallery.DayScreen
import dev.kodakiko.ui.feature.gallery.GalleryScreen
import dev.kodakiko.ui.feature.gallery.TrashScreen
import dev.kodakiko.ui.feature.lock.LockScreen
import dev.kodakiko.ui.feature.viewer.EntryDetailsScreen
import dev.kodakiko.ui.feature.viewer.EntryViewerScreen
import dev.kodakiko.ui.feature.viewer.VideoPlayerScreen

enum class AppScreen {
    WELCOME,
    SIGN_UP,
    SECURING,
    RECOVERY_KEY,
    SIGN_IN,
    RECOVER,
    CANCEL_DELETION,
    LOCAL_SETUP,
    CONNECT_SERVER,
    LOCK,
    GALLERY,
    TRASH,
    DAY,
    CALENDAR,
    ENTRY,
    ENTRY_DETAILS,
    PLAY,
    PLACEHOLDER,
}

object AppScreens {
    fun of(route: Route): AppScreen = when (route) {
        Route.Welcome -> AppScreen.WELCOME
        Route.SignUp -> AppScreen.SIGN_UP
        Route.Securing -> AppScreen.SECURING
        Route.RecoveryKey -> AppScreen.RECOVERY_KEY
        Route.SignIn -> AppScreen.SIGN_IN
        Route.Recover -> AppScreen.RECOVER
        Route.CancelDeletion -> AppScreen.CANCEL_DELETION
        Route.LocalSetup -> AppScreen.LOCAL_SETUP
        Route.SettingsConnect -> AppScreen.CONNECT_SERVER
        Route.Lock -> AppScreen.LOCK
        Route.Gallery -> AppScreen.GALLERY
        Route.Trash -> AppScreen.TRASH
        is Route.Day -> AppScreen.DAY
        Route.Calendar -> AppScreen.CALENDAR
        is Route.Entry -> AppScreen.ENTRY
        is Route.EntryDetails -> AppScreen.ENTRY_DETAILS
        is Route.Play -> AppScreen.PLAY
        else -> AppScreen.PLACEHOLDER
    }

    @Composable
    fun Content(route: Route) {
        when (of(route)) {
            AppScreen.WELCOME -> WelcomeScreen()
            AppScreen.SIGN_UP -> SignUpScreen()
            AppScreen.SECURING -> SecuringScreen()
            AppScreen.RECOVERY_KEY -> RecoveryKeyScreen()
            AppScreen.SIGN_IN -> SignInScreen()
            AppScreen.RECOVER -> RecoverScreen()
            AppScreen.CANCEL_DELETION -> CancelDeletionScreen()
            AppScreen.LOCAL_SETUP -> LocalSetupScreen()
            AppScreen.CONNECT_SERVER -> ConnectServerScreen()
            AppScreen.LOCK -> LockScreen()
            AppScreen.GALLERY -> GalleryScreen()
            AppScreen.TRASH -> TrashScreen()
            AppScreen.DAY -> DayScreen((route as Route.Day).date)
            AppScreen.CALENDAR -> CalendarScreen()
            AppScreen.ENTRY -> EntryViewerScreen((route as Route.Entry).entryId)
            AppScreen.ENTRY_DETAILS -> EntryDetailsScreen((route as Route.EntryDetails).entryId)
            AppScreen.PLAY -> (route as Route.Play).let { VideoPlayerScreen(it.entryId, it.mediaId) }
            AppScreen.PLACEHOLDER -> PlaceholderScreen()
        }
    }
}

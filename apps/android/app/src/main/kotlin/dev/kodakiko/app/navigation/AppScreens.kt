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
import dev.kodakiko.ui.feature.capture.CaptureScreen
import dev.kodakiko.ui.feature.edit.CropScreen
import dev.kodakiko.ui.feature.edit.EditScreen
import dev.kodakiko.ui.feature.edit.EntryNewScreen
import dev.kodakiko.ui.feature.gallery.DayScreen
import dev.kodakiko.ui.feature.gallery.GalleryScreen
import dev.kodakiko.ui.feature.gallery.TrashScreen
import dev.kodakiko.ui.feature.links.LinkCreateScreen
import dev.kodakiko.ui.feature.links.LinksListScreen
import dev.kodakiko.ui.feature.lock.LockScreen
import dev.kodakiko.ui.feature.sharing.CollectionScreen
import dev.kodakiko.ui.feature.sharing.ShareSheetScreen
import dev.kodakiko.ui.feature.sharing.ShareWithScreen
import dev.kodakiko.ui.feature.sharing.SharedScreen
import dev.kodakiko.ui.feature.tags.TagsScreen
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
    ENTRY_EDIT,
    ENTRY_NEW,
    ENTRY_CROP,
    CAPTURE,
    TAGS,
    SHARED,
    COLLECTION,
    COLLECTION_SHARE,
    SHARE_SHEET,
    LINK_NEW,
    SETTINGS_LINKS,
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
        is Route.EntryEdit -> AppScreen.ENTRY_EDIT
        Route.EntryNew -> AppScreen.ENTRY_NEW
        is Route.EntryCrop -> AppScreen.ENTRY_CROP
        is Route.Capture -> AppScreen.CAPTURE
        Route.Tags -> AppScreen.TAGS
        Route.Shared -> AppScreen.SHARED
        is Route.Collection -> AppScreen.COLLECTION
        is Route.CollectionShare -> AppScreen.COLLECTION_SHARE
        is Route.ShareSheet -> AppScreen.SHARE_SHEET
        is Route.LinkNew -> AppScreen.LINK_NEW
        Route.SettingsLinks -> AppScreen.SETTINGS_LINKS
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
            AppScreen.ENTRY_EDIT -> (route as Route.EntryEdit).let { EditScreen(it.entryId, it.isNew) }
            AppScreen.ENTRY_NEW -> EntryNewScreen()
            AppScreen.ENTRY_CROP -> (route as Route.EntryCrop).let { CropScreen(it.entryId, it.mediaId) }
            AppScreen.CAPTURE -> (route as Route.Capture).let { CaptureScreen(it.entryId, it.mode) }
            AppScreen.TAGS -> TagsScreen()
            AppScreen.SHARED -> SharedScreen()
            AppScreen.COLLECTION -> CollectionScreen((route as Route.Collection).collectionId)
            AppScreen.COLLECTION_SHARE -> ShareWithScreen((route as Route.CollectionShare).collectionId)
            AppScreen.SHARE_SHEET -> (route as Route.ShareSheet).let { ShareSheetScreen(it.entryIds, it.inKodakiko) }
            AppScreen.LINK_NEW -> LinkCreateScreen((route as Route.LinkNew).entryIds)
            AppScreen.SETTINGS_LINKS -> LinksListScreen()
            AppScreen.PLACEHOLDER -> PlaceholderScreen()
        }
    }
}

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
import dev.kodakiko.ui.feature.more.MoreScreen
import dev.kodakiko.ui.feature.more.StatisticsScreen
import dev.kodakiko.ui.feature.settings.AboutScreen
import dev.kodakiko.ui.feature.settings.AccountSettingsScreen
import dev.kodakiko.ui.feature.settings.AdminScreen
import dev.kodakiko.ui.feature.settings.AdvancedSettingsScreen
import dev.kodakiko.ui.feature.settings.AppearanceSettingsScreen
import dev.kodakiko.ui.feature.settings.CaptureSettingsScreen
import dev.kodakiko.ui.feature.settings.DebugLogScreen
import dev.kodakiko.ui.feature.settings.DeleteAccountScreen
import dev.kodakiko.ui.feature.settings.DiagnosticsScreen
import dev.kodakiko.ui.feature.settings.ExportScreen
import dev.kodakiko.ui.feature.settings.GallerySettingsScreen
import dev.kodakiko.ui.feature.settings.ImportScreen
import dev.kodakiko.ui.feature.settings.PasswordScreen
import dev.kodakiko.ui.feature.settings.SecuritySettingsScreen
import dev.kodakiko.ui.feature.settings.ServerKeysScreen
import dev.kodakiko.ui.feature.settings.SettingsScreen
import dev.kodakiko.ui.feature.settings.SettingsSearchScreen
import dev.kodakiko.ui.feature.settings.StorageSettingsScreen
import dev.kodakiko.ui.feature.settings.SyncSettingsScreen
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
    SETTINGS,
    SETTINGS_SEARCH,
    SETTINGS_APPEARANCE,
    SETTINGS_GALLERY,
    SETTINGS_CAPTURE,
    SETTINGS_SYNC,
    SETTINGS_STORAGE,
    SETTINGS_SECURITY,
    SETTINGS_EXPORT,
    SETTINGS_IMPORT,
    MORE,
    STATISTICS,
    SETTINGS_ACCOUNT,
    SETTINGS_SERVER,
    SETTINGS_PASSWORD,
    SETTINGS_DELETE_ACCOUNT,
    SETTINGS_ADMIN,
    SETTINGS_ADVANCED,
    SETTINGS_DIAGNOSTICS,
    SETTINGS_DEBUG_LOG,
    SETTINGS_ABOUT,
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
        Route.Settings -> AppScreen.SETTINGS
        Route.SettingsSearch -> AppScreen.SETTINGS_SEARCH
        Route.SettingsAppearance -> AppScreen.SETTINGS_APPEARANCE
        Route.SettingsGallery -> AppScreen.SETTINGS_GALLERY
        Route.SettingsCapture -> AppScreen.SETTINGS_CAPTURE
        Route.SettingsSync -> AppScreen.SETTINGS_SYNC
        Route.SettingsStorage -> AppScreen.SETTINGS_STORAGE
        Route.SettingsSecurity -> AppScreen.SETTINGS_SECURITY
        Route.SettingsExport -> AppScreen.SETTINGS_EXPORT
        Route.SettingsImport -> AppScreen.SETTINGS_IMPORT
        Route.More -> AppScreen.MORE
        Route.Statistics -> AppScreen.STATISTICS
        Route.SettingsAccount -> AppScreen.SETTINGS_ACCOUNT
        Route.SettingsServer -> AppScreen.SETTINGS_SERVER
        Route.SettingsPassword -> AppScreen.SETTINGS_PASSWORD
        Route.SettingsDeleteAccount -> AppScreen.SETTINGS_DELETE_ACCOUNT
        Route.SettingsAdmin -> AppScreen.SETTINGS_ADMIN
        Route.SettingsAdvanced -> AppScreen.SETTINGS_ADVANCED
        Route.SettingsDiagnostics -> AppScreen.SETTINGS_DIAGNOSTICS
        Route.SettingsDebugLog -> AppScreen.SETTINGS_DEBUG_LOG
        Route.SettingsAbout -> AppScreen.SETTINGS_ABOUT
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
            AppScreen.SETTINGS -> SettingsScreen()
            AppScreen.SETTINGS_SEARCH -> SettingsSearchScreen()
            AppScreen.SETTINGS_APPEARANCE -> AppearanceSettingsScreen()
            AppScreen.SETTINGS_GALLERY -> GallerySettingsScreen()
            AppScreen.SETTINGS_CAPTURE -> CaptureSettingsScreen()
            AppScreen.SETTINGS_SYNC -> SyncSettingsScreen()
            AppScreen.SETTINGS_STORAGE -> StorageSettingsScreen()
            AppScreen.SETTINGS_SECURITY -> SecuritySettingsScreen()
            AppScreen.SETTINGS_EXPORT -> ExportScreen()
            AppScreen.SETTINGS_IMPORT -> ImportScreen()
            AppScreen.MORE -> MoreScreen()
            AppScreen.STATISTICS -> StatisticsScreen()
            AppScreen.SETTINGS_ACCOUNT -> AccountSettingsScreen()
            AppScreen.SETTINGS_SERVER -> ServerKeysScreen()
            AppScreen.SETTINGS_PASSWORD -> PasswordScreen()
            AppScreen.SETTINGS_DELETE_ACCOUNT -> DeleteAccountScreen()
            AppScreen.SETTINGS_ADMIN -> AdminScreen()
            AppScreen.SETTINGS_ADVANCED -> AdvancedSettingsScreen()
            AppScreen.SETTINGS_DIAGNOSTICS -> DiagnosticsScreen()
            AppScreen.SETTINGS_DEBUG_LOG -> DebugLogScreen()
            AppScreen.SETTINGS_ABOUT -> AboutScreen()
            AppScreen.PLACEHOLDER -> PlaceholderScreen()
        }
    }
}

package dev.kodakiko.ui.core.navigation

object RoutePaths {
    const val SEPARATOR = "/"
    const val ROOT = "/"

    const val WELCOME = "welcome"
    const val INVITE = "invite"
    const val SIGN_UP = "sign-up"
    const val SIGN_IN = "sign-in"
    const val SECURING = "securing"
    const val RECOVERY_KEY = "recovery-key"
    const val RECOVER = "recover"
    const val CANCEL_DELETION = "cancel-deletion"
    const val LOCAL_SETUP = "local-setup"
    const val LOCK = "lock"
    const val CALENDAR = "calendar"
    const val SHARED = "shared"
    const val MORE = "more"
    const val TRASH = "trash"
    const val DAY = "day"
    const val ENTRY = "entry"
    const val DETAILS = "details"
    const val EDIT = "edit"
    const val CROP = "crop"
    const val NEW = "new"
    const val PLAY = "play"
    const val CAPTURE = "capture"
    const val TAGS = "tags"
    const val COLLECTIONS = "collections"
    const val SHARE = "share"
    const val APPS = "apps"
    const val LIST_SEPARATOR = ","
    const val LINKS = "links"
    const val STATISTICS = "statistics"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val APPEARANCE = "appearance"
    const val GALLERY = "gallery"
    const val SYNC = "sync"
    const val STORAGE = "storage"
    const val SECURITY = "security"
    const val ACCOUNT = "account"
    const val SERVER = "server"
    const val PASSWORD = "password"
    const val DELETE_ACCOUNT = "delete-account"
    const val CONNECT = "connect"
    const val ADMIN = "admin"
    const val ADVANCED = "advanced"
    const val DIAGNOSTICS = "diagnostics"
    const val ABOUT = "about"
    const val EXPORT = "export"
    const val IMPORT = "import"

    fun of(vararg segments: String): String = SEPARATOR + segments.joinToString(SEPARATOR)
}

internal object RouteConstants {
    const val SINGLE_SEGMENT = 1
    const val TWO_SEGMENTS = 2
    const val THREE_SEGMENTS = 3
    const val FOUR_SEGMENTS = 4
    const val SECOND = 1
    const val THIRD = 2
    const val FOURTH = 3
    const val NO_NAVIGATOR = "No RouteNavigator provided"
}

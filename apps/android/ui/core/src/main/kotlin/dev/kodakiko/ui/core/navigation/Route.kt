package dev.kodakiko.ui.core.navigation

import dev.kodakiko.ui.core.navigation.RoutePaths.of
import java.io.Serializable

enum class CaptureMode {
    PHOTO,
    VIDEO,
}

sealed interface Route : Serializable {
    val path: String

    sealed interface Tab : Route

    data object Home : Route {
        override val path: String get() = RoutePaths.ROOT
    }

    data object Welcome : Route {
        override val path: String get() = of(RoutePaths.WELCOME)
    }

    data object Invite : Route {
        override val path: String get() = of(RoutePaths.INVITE)
    }

    data object SignUp : Route {
        override val path: String get() = of(RoutePaths.SIGN_UP)
    }

    data object SignIn : Route {
        override val path: String get() = of(RoutePaths.SIGN_IN)
    }

    data object Securing : Route {
        override val path: String get() = of(RoutePaths.SECURING)
    }

    data object RecoveryKey : Route {
        override val path: String get() = of(RoutePaths.RECOVERY_KEY)
    }

    data object Recover : Route {
        override val path: String get() = of(RoutePaths.RECOVER)
    }

    data object CancelDeletion : Route {
        override val path: String get() = of(RoutePaths.CANCEL_DELETION)
    }

    data object LocalSetup : Route {
        override val path: String get() = of(RoutePaths.LOCAL_SETUP)
    }

    data object Lock : Route {
        override val path: String get() = of(RoutePaths.LOCK)
    }

    data object Gallery : Tab {
        override val path: String get() = RoutePaths.ROOT
    }

    data object Calendar : Tab {
        override val path: String get() = of(RoutePaths.CALENDAR)
    }

    data object Shared : Tab {
        override val path: String get() = of(RoutePaths.SHARED)
    }

    data object More : Tab {
        override val path: String get() = of(RoutePaths.MORE)
    }

    data object Trash : Route {
        override val path: String get() = of(RoutePaths.TRASH)
    }

    data class Day(val date: String) : Route {
        override val path: String get() = of(RoutePaths.DAY, date)
    }

    data class Entry(val entryId: String) : Route {
        override val path: String get() = of(RoutePaths.ENTRY, entryId)
    }

    data class EntryDetails(val entryId: String) : Route {
        override val path: String get() = of(RoutePaths.ENTRY, entryId, RoutePaths.DETAILS)
    }

    data class EntryEdit(val entryId: String, val isNew: Boolean = false) : Route {
        override val path: String get() = of(RoutePaths.ENTRY, entryId, RoutePaths.EDIT)
    }

    data class EntryCrop(val entryId: String, val mediaId: String) : Route {
        override val path: String get() = of(RoutePaths.ENTRY, entryId, RoutePaths.CROP, mediaId)
    }

    data object EntryNew : Route {
        override val path: String get() = of(RoutePaths.ENTRY, RoutePaths.NEW)
    }

    data class Play(val entryId: String, val mediaId: String) : Route {
        override val path: String get() = of(RoutePaths.PLAY, entryId, mediaId)
    }

    data class Capture(val entryId: String? = null, val mode: CaptureMode = CaptureMode.PHOTO) : Route {
        override val path: String get() = of(RoutePaths.CAPTURE)
    }

    data object Tags : Route {
        override val path: String get() = of(RoutePaths.TAGS)
    }

    data class Collection(val collectionId: String) : Route {
        override val path: String get() = of(RoutePaths.COLLECTIONS, collectionId)
    }

    data class CollectionShare(val collectionId: String) : Route {
        override val path: String get() = of(RoutePaths.COLLECTIONS, collectionId, RoutePaths.SHARE)
    }

    data class LinkNew(val entryIds: List<String>) : Route {
        override val path: String get() = of(RoutePaths.LINKS, RoutePaths.NEW)
    }

    data class ShareSheet(val entryIds: List<String>, val inKodakiko: Boolean) : Route {
        override val path: String
            get() {
                val segments = buildList {
                    add(RoutePaths.SHARE)
                    if (entryIds.isNotEmpty()) add(entryIds.joinToString(RoutePaths.LIST_SEPARATOR))
                    if (!inKodakiko) add(RoutePaths.APPS)
                }
                return of(*segments.toTypedArray())
            }
    }

    data object Statistics : Route {
        override val path: String get() = of(RoutePaths.STATISTICS)
    }

    data object Settings : Route {
        override val path: String get() = of(RoutePaths.SETTINGS)
    }

    data object SettingsSearch : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.SEARCH)
    }

    data object SettingsAppearance : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.APPEARANCE)
    }

    data object SettingsGallery : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.GALLERY)
    }

    data object SettingsCapture : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.CAPTURE)
    }

    data object SettingsSync : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.SYNC)
    }

    data object SettingsStorage : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.STORAGE)
    }

    data object SettingsSecurity : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.SECURITY)
    }

    data object SettingsAccount : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.ACCOUNT)
    }

    data object SettingsServer : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.SERVER)
    }

    data object SettingsPassword : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.PASSWORD)
    }

    data object SettingsDeleteAccount : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.DELETE_ACCOUNT)
    }

    data object SettingsConnect : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.CONNECT)
    }

    data object SettingsLinks : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.LINKS)
    }

    data object SettingsAdmin : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.ADMIN)
    }

    data object SettingsAdvanced : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.ADVANCED)
    }

    data object SettingsDiagnostics : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.DIAGNOSTICS)
    }

    data object SettingsAbout : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.ABOUT)
    }

    data object SettingsExport : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.EXPORT)
    }

    data object SettingsImport : Route {
        override val path: String get() = of(RoutePaths.SETTINGS, RoutePaths.IMPORT)
    }

    companion object {
        val tabs: List<Tab> = listOf(Gallery, Calendar, Shared, More)

        private val singles: List<Route> = listOf(
            Welcome,
            Invite,
            SignUp,
            SignIn,
            Securing,
            RecoveryKey,
            Recover,
            CancelDeletion,
            LocalSetup,
            Lock,
            Calendar,
            Shared,
            More,
            Trash,
            Capture(),
            Tags,
            ShareSheet(emptyList(), true),
            Statistics,
            Settings,
        )

        private val settingsScreens: List<Route> = listOf(
            SettingsSearch,
            SettingsAppearance,
            SettingsGallery,
            SettingsCapture,
            SettingsSync,
            SettingsStorage,
            SettingsSecurity,
            SettingsAccount,
            SettingsServer,
            SettingsPassword,
            SettingsDeleteAccount,
            SettingsConnect,
            SettingsLinks,
            SettingsAdmin,
            SettingsAdvanced,
            SettingsDiagnostics,
            SettingsAbout,
            SettingsExport,
            SettingsImport,
        )

        private val bySingleSegment: Map<String, Route> by lazy { singles.associateBy { it.path.removePrefix(RoutePaths.SEPARATOR) } }

        private val bySettingsSegment: Map<String, Route> by lazy {
            settingsScreens.associateBy { it.path.substringAfterLast(RoutePaths.SEPARATOR) }
        }

        fun fromPath(path: String): Route? {
            if (!path.startsWith(RoutePaths.SEPARATOR)) return null
            if (path == RoutePaths.ROOT) return Gallery
            val segments = path.removePrefix(RoutePaths.SEPARATOR).split(RoutePaths.SEPARATOR)
            if (segments.any { it.isEmpty() }) return null
            return when (segments.size) {
                RouteConstants.SINGLE_SEGMENT -> bySingleSegment[segments.first()]
                RouteConstants.TWO_SEGMENTS -> twoSegments(segments.first(), segments[RouteConstants.SECOND])
                RouteConstants.THREE_SEGMENTS -> threeSegments(segments.first(), segments[RouteConstants.SECOND], segments[RouteConstants.THIRD])
                RouteConstants.FOUR_SEGMENTS -> fourSegments(segments)
                else -> null
            }
        }

        private fun twoSegments(first: String, second: String): Route? = when (first) {
            RoutePaths.DAY -> Day(second)
            RoutePaths.ENTRY -> if (second == RoutePaths.NEW) EntryNew else Entry(second)
            RoutePaths.COLLECTIONS -> Collection(second)
            RoutePaths.LINKS -> if (second == RoutePaths.NEW) LinkNew(emptyList()) else null
            RoutePaths.SHARE -> if (second == RoutePaths.APPS) ShareSheet(emptyList(), false) else ShareSheet(entryIdsOf(second), true)
            RoutePaths.SETTINGS -> bySettingsSegment[second]
            else -> null
        }

        private fun fourSegments(segments: List<String>): Route? =
            if (segments.first() == RoutePaths.ENTRY && segments[RouteConstants.THIRD] == RoutePaths.CROP) {
                EntryCrop(segments[RouteConstants.SECOND], segments[RouteConstants.FOURTH])
            } else {
                null
            }

        private fun threeSegments(first: String, second: String, third: String): Route? = when {
            first == RoutePaths.ENTRY && third == RoutePaths.DETAILS -> EntryDetails(second)
            first == RoutePaths.ENTRY && third == RoutePaths.EDIT -> EntryEdit(second)
            first == RoutePaths.PLAY -> Play(second, third)
            first == RoutePaths.COLLECTIONS && third == RoutePaths.SHARE -> CollectionShare(second)
            first == RoutePaths.SHARE && third == RoutePaths.APPS -> ShareSheet(entryIdsOf(second), false)
            else -> null
        }

        private fun entryIdsOf(segment: String): List<String> = segment.split(RoutePaths.LIST_SEPARATOR)
    }
}

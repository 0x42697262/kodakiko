package dev.kodakiko.service.lock

object LockStepNames {
    const val CANCEL_EXPORT = "cancelExport"
    const val STOP_SYNC = "stopSync"
    const val CANCEL_DOWNLOADS = "cancelDownloads"
    const val RELEASE_VIDEO = "releaseVideo"
    const val RELEASE_ORIGINALS = "releaseOriginals"
    const val CLEAR_THUMBNAILS = "clearThumbnails"
    const val CLEAR_TRAY = "clearTray"
    const val WIPE_TEMP = "wipeTemp"
    const val CLEAR_INDEX = "clearIndex"
    const val CLEAR_UI_STATE = "clearUiState"
    const val CLEAR_COLLECTION_NAMES = "clearCollectionNames"
    const val CLEAR_ACCOUNT = "clearAccount"
    const val WIPE_KEYS = "wipeKeys"

    const val LOAD_SETTINGS = "loadSettings"
    const val START_SYNC = "startSync"
    const val BUILD_INDEX = "buildIndex"
    const val SWEEP_ORPHANS = "sweepOrphans"
    const val ENFORCE_CACHE = "enforceCache"
    const val REFRESH_ME = "refreshMe"

    val LOCK: List<String> = listOf(
        CANCEL_EXPORT,
        STOP_SYNC,
        CANCEL_DOWNLOADS,
        RELEASE_VIDEO,
        RELEASE_ORIGINALS,
        CLEAR_THUMBNAILS,
        CLEAR_TRAY,
        WIPE_TEMP,
        CLEAR_INDEX,
        CLEAR_UI_STATE,
        CLEAR_COLLECTION_NAMES,
        CLEAR_ACCOUNT,
        WIPE_KEYS,
    )

    val UNLOCK: List<String> = listOf(LOAD_SETTINGS, START_SYNC, BUILD_INDEX, SWEEP_ORPHANS, ENFORCE_CACHE, REFRESH_ME)
}

object LockLogEvents {
    const val STEP_FAILED = "lock.step_failed"
    const val UNLOCK_STEP_FAILED = "unlock.step_failed"
}

object LockConstants {
    const val MS_PER_MINUTE = 60_000L
}

object LockRoutes {
    const val HOME = "/"
    val ENTRY_PATTERN = Regex("^/entry/([^/]+)")
    const val ENTRY_ID_GROUP = 1
    val UNPROTECTED: List<Regex> = listOf(
        Regex("^/welcome\\z"),
        Regex("^/invite\\z"),
        Regex("^/sign-in\\z"),
        Regex("^/sign-up\\z"),
        Regex("^/securing\\z"),
        Regex("^/recover\\z"),
        Regex("^/lock\\z"),
        Regex("^/cancel-deletion\\z"),
        Regex("^/s/[^/]+\\z"),
    )
}

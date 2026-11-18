package dev.kodakiko.service.sync

import dev.kodakiko.data.secure.AccountMode

enum class SyncBannerId(val wire: String) {
    OFFLINE("offline"),
    DEAD("dead"),
    WAITING_FOR_WIFI("waitingForWifi"),
}

enum class SyncBannerKind {
    INFO,
    WARNING,
}

data class SyncBannerModel(val id: SyncBannerId, val kind: SyncBannerKind, val count: Int, val retry: Boolean)

fun syncBannerModel(status: SyncStatus): SyncBannerModel? {
    if (status.offline) return SyncBannerModel(SyncBannerId.OFFLINE, SyncBannerKind.INFO, count = 0, retry = false)
    if (status.dead > 0) return SyncBannerModel(SyncBannerId.DEAD, SyncBannerKind.WARNING, count = status.dead, retry = true)
    if (status.waitingForWifi > 0 && status.queued == 0) {
        return SyncBannerModel(SyncBannerId.WAITING_FOR_WIFI, SyncBannerKind.INFO, count = status.waitingForWifi, retry = false)
    }
    return null
}

fun pullToSyncEnabled(mode: AccountMode, paused: Boolean): Boolean = mode == AccountMode.SERVER && !paused

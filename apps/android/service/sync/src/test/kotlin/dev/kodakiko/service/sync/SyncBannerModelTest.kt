package dev.kodakiko.service.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncBannerModelTest {
    private fun status(
        running: Boolean = false,
        queued: Int = 0,
        dead: Int = 0,
        waitingForWifi: Int = 0,
        lastSuccessAt: Long? = null,
        offline: Boolean = false,
    ): SyncStatus = SyncStatus(running, queued, dead, waitingForWifi, lastSuccessAt, offline)

    @Test
    fun isHiddenWhenEverythingIsFine() {
        assertNull(syncBannerModel(status()))
        assertNull(syncBannerModel(status(queued = 3, running = true)))
    }

    @Test
    fun showsTheOfflineInfoFirstAheadOfDeadItems() {
        assertEquals(
            SyncBannerModel(SyncBannerId.OFFLINE, SyncBannerKind.INFO, count = 0, retry = false),
            syncBannerModel(status(offline = true, dead = 2)),
        )
    }

    @Test
    fun showsAWarningWithARetryActionForDeadItemsSingularAndPlural() {
        assertEquals(
            SyncBannerModel(SyncBannerId.DEAD, SyncBannerKind.WARNING, count = 1, retry = true),
            syncBannerModel(status(dead = 1)),
        )
        assertEquals(4, syncBannerModel(status(dead = 4))?.count)
    }

    @Test
    fun showsTheWifiInfoOnlyWhenNothingElseIsQueued() {
        assertEquals(
            SyncBannerModel(SyncBannerId.WAITING_FOR_WIFI, SyncBannerKind.INFO, count = 1, retry = false),
            syncBannerModel(status(waitingForWifi = 1)),
        )
        assertEquals(3, syncBannerModel(status(waitingForWifi = 3))?.count)
        assertNull(syncBannerModel(status(waitingForWifi = 3, queued = 2)))
    }

    @Test
    fun prefersDeadItemsOverTheWifiHint() {
        assertEquals(SyncBannerId.DEAD, syncBannerModel(status(dead = 1, waitingForWifi = 2))?.id)
    }

    @Test
    fun bannerIdsKeepTheV031WireNames() {
        assertEquals(listOf("offline", "dead", "waitingForWifi"), SyncBannerId.entries.map { it.wire })
    }
}

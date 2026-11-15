package dev.kodakiko.ui.core.format

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.service.sharing.CollectionName
import dev.kodakiko.service.sync.SyncBannerId
import dev.kodakiko.service.sync.SyncBannerKind
import dev.kodakiko.service.sync.SyncBannerModel
import dev.kodakiko.service.sync.SyncStatus
import dev.kodakiko.service.sync.syncBannerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun status(queued: Int = 0, dead: Int = 0, waitingForWifi: Int = 0, offline: Boolean = false, running: Boolean = false) =
    SyncStatus(running = running, queued = queued, dead = dead, waitingForWifi = waitingForWifi, lastSuccessAt = null, offline = offline)

@RunWith(RobolectricTestRunner::class)
class ServiceMessageTextTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun message(status: SyncStatus): String? = syncBannerModel(status)?.let { syncBannerText(resources, it) }

    @Test
    fun syncBannerIsHiddenWhenEverythingIsFine() {
        assertNull(message(status()))
        assertNull(message(status(queued = 3, running = true)))
    }

    @Test
    fun syncBannerShowsTheOfflineInfoFirstAheadOfDeadItems() {
        assertEquals("Offline. Changes will sync when you're connected.", message(status(offline = true, dead = 2)))
    }

    @Test
    fun syncBannerShowsAWarningForDeadItemsSingularAndPlural() {
        assertEquals("Couldn't sync 1 item.", message(status(dead = 1)))
        assertEquals("Couldn't sync 4 items.", message(status(dead = 4)))
    }

    @Test
    fun syncBannerShowsTheWifiInfoOnlyWhenNothingElseIsQueued() {
        assertEquals("Waiting for Wi-Fi to upload 1 large item.", message(status(waitingForWifi = 1)))
        assertEquals("Waiting for Wi-Fi to upload 3 large items.", message(status(waitingForWifi = 3)))
        assertNull(message(status(waitingForWifi = 3, queued = 2)))
    }

    @Test
    fun syncBannerPrefersDeadItemsOverTheWifiHint() {
        assertEquals("Couldn't sync 1 item.", message(status(dead = 1, waitingForWifi = 2)))
    }

    @Test
    fun syncBannerTextDependsOnlyOnIdAndCount() {
        assertEquals("Offline. Changes will sync when you're connected.", syncBannerText(resources, SyncBannerModel(SyncBannerId.OFFLINE, SyncBannerKind.INFO, 0, false)))
        assertEquals("Couldn't sync 0 items.", syncBannerText(resources, SyncBannerModel(SyncBannerId.DEAD, SyncBannerKind.WARNING, 0, true)))
        assertEquals("Couldn't sync 21 items.", syncBannerText(resources, SyncBannerModel(SyncBannerId.DEAD, SyncBannerKind.WARNING, 21, true)))
        assertEquals("Waiting for Wi-Fi to upload 2 large items.", syncBannerText(resources, SyncBannerModel(SyncBannerId.WAITING_FOR_WIFI, SyncBannerKind.INFO, 2, false)))
    }

    @Test
    fun collectionNamesFallBackToUntitledJournal() {
        assertEquals("Untitled journal", collectionNameText(resources, CollectionName.Unnamed))
        assertEquals("Trip", collectionNameText(resources, CollectionName.Named("Trip")))
        assertEquals("Renamed", collectionNameText(resources, CollectionName.Named("Renamed")))
    }
}

package dev.kodakiko.app.navigation

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import dev.kodakiko.app.FakeKodakikoServer.Companion.GET
import dev.kodakiko.app.ServerFixture
import dev.kodakiko.app.ServerV031App
import dev.kodakiko.app.ServerV031HeldBucketApp
import dev.kodakiko.app.ServerV031RemoteOriginalApp
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.V031Media
import dev.kodakiko.contract.Routes
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SyncWiringTest : AppWiringHarness() {
    private val syncState: String = Routes.syncState()

    private fun pulls(): Int = ServerFixture.requireServer().count(GET, syncState)

    private fun downloads(objectId: String): Int = ServerFixture.requireServer().count(GET, Routes.objectDownload(objectId))

    @Test
    @Config(application = ServerV031App::class)
    fun unlockingAServerAccountStartsSyncWhichPullsFromTheServer() {
        launch()
        awaitTag(TestTags.Lock.SCREEN, UNLOCK_WAIT_MS)
        assertEquals(0, pulls())
        enterPassword()
        awaitTag(listItem(TestTags.Gallery.TILE, 0), UNLOCK_WAIT_MS)
        compose.waitUntil(SYNC_WAIT_MS) { pulls() >= 1 }
        assertEquals(AccountMode.SERVER, app.graph.authService.mode.value)
    }

    @Test
    @Config(application = ServerV031App::class)
    fun lockingStopsSyncSoTheNextUnlockStartsItAgain() {
        unlockToGallery()
        compose.waitUntil(SYNC_WAIT_MS) { pulls() >= 1 }
        lockFromGallery()
        val whileLocked = pulls()
        enterPassword()
        awaitTag(listItem(TestTags.Gallery.TILE, 0), UNLOCK_WAIT_MS)
        compose.waitUntil(SYNC_WAIT_MS) { pulls() > whileLocked }
    }

    @Test
    @Config(application = ServerV031App::class)
    fun pullToSyncOnTheGalleryRunsARealSyncPass() {
        unlockToGallery()
        compose.waitUntil(SYNC_WAIT_MS) { pulls() >= 1 }
        val before = pulls()
        pullToSync()
        compose.waitUntil(SYNC_WAIT_MS) { pulls() > before }
    }

    @Test
    @Config(application = ServerV031App::class)
    fun pullToSyncOnTheSharedTabRunsARealSyncPass() {
        unlockToGallery()
        compose.waitUntil(SYNC_WAIT_MS) { pulls() >= 1 }
        tap(TestTags.Tabs.SHARED)
        awaitTag(TestTags.Shared.EMPTY_MINE)
        val before = pulls()
        pullToSync(TestTags.Shared.SCREEN)
        compose.waitUntil(SYNC_WAIT_MS) { pulls() > before }
    }

    @Test
    @Config(application = ServerV031App::class)
    fun syncNowInSyncSettingsRunsARealSyncPass() {
        unlockToGallery()
        compose.waitUntil(SYNC_WAIT_MS) { pulls() >= 1 }
        push(Route.SettingsSync)
        awaitTag(TestTags.Settings.SYNC_SCREEN)
        awaitTag(TestTags.Settings.SYNC_NOW)
        assertFalse(exists(TestTags.Settings.SYNC_LOCAL_ONLY))
        compose.waitUntil(SYNC_WAIT_MS) { compose.onAllNodes(isEnabled() and hasTestTag(TestTags.Settings.SYNC_NOW)).fetchSemanticsNodes().isNotEmpty() }
        val before = pulls()
        click(TestTags.Settings.SYNC_NOW)
        compose.waitUntil(SYNC_WAIT_MS) { pulls() > before }
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun aLocalJournalsSyncSettingsOfferToConnectAServerInsteadOfSyncNow() {
        unlockToGallery()
        push(Route.SettingsSync)
        awaitTag(TestTags.Settings.SYNC_LOCAL_ONLY)
        assertTrue(exists(TestTags.Settings.SYNC_CONNECT))
        assertFalse(exists(TestTags.Settings.SYNC_NOW))
    }

    @Test
    @Config(application = ServerV031App::class)
    fun aMissingThumbnailIsDownloadedThroughTheDownloadQueueAndStoredAsTheV031PlainThumbnail() {
        assertFalse(V031Media.plainThumbnail(app).exists())
        unlockToGallery()
        compose.waitUntil(SYNC_WAIT_MS) { V031Media.plainThumbnail(app).exists() && V031Media.plainThumbnail(app).length() == V031Media.fixturePlainThumbnail.size.toLong() }
        assertArrayEquals(V031Media.fixturePlainThumbnail, V031Media.plainThumbnail(app).readBytes())
        assertTrue(downloads(V031Media.THUMBNAIL_OBJECT) >= 1)
        assertTrue(ServerFixture.requireBucket().requests.any { it.contains("/bucket/" + V031Media.THUMBNAIL_OBJECT) })
    }

    @Test
    @Config(application = ServerV031HeldBucketApp::class)
    fun lockingAbortsAThumbnailDownloadThatIsStillRunning() {
        unlockToGallery()
        compose.waitUntil(SYNC_WAIT_MS) { ServerFixture.requireBucket().requests.isNotEmpty() }
        assertTrue(downloads(V031Media.THUMBNAIL_OBJECT) >= 1)
        assertFalse(ServerFixture.requireBucket().awaitHeldConnectionClosed(0))
        lockFromGallery()
        assertTrue(ServerFixture.requireBucket().awaitHeldConnectionClosed(ABORT_WAIT_MS))
        assertFalse(V031Media.plainThumbnail(app).exists())
    }

    @Test
    @Config(application = ServerV031RemoteOriginalApp::class)
    fun openingAnEntryWhoseOriginalIsOnlyOnTheServerDownloadsItThroughTheDownloadQueue() {
        assertFalse(V031Media.objectFile(app, V031Media.ORIGINAL_OBJECT).exists())
        unlockToGallery()
        click(listItem(TestTags.Gallery.TILE, 0))
        awaitTag(TestTags.Viewer.SCREEN)
        compose.waitUntil(SYNC_WAIT_MS) { downloads(V031Media.ORIGINAL_OBJECT) >= 1 }
        val original = V031Media.objectFile(app, V031Media.ORIGINAL_OBJECT)
        compose.waitUntil(SYNC_WAIT_MS) { original.exists() && original.length() == V031Media.fixtureOriginalCiphertext.size.toLong() }
        assertArrayEquals(V031Media.fixtureOriginalCiphertext, original.readBytes())
    }

    private companion object {
        const val ABORT_WAIT_MS = 15_000L
    }
}

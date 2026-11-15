@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.shareout

import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.platform.picker.PickerConstants
import dev.kodakiko.service.sharing.ShareSelection
import dev.kodakiko.service.sharing.ShareTextOption
import dev.kodakiko.service.sharing.SharingConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

private const val AUTHORITY = "dev.kodakiko.test.shareout"
private const val RELEASE_MS = 60_000L

@RunWith(RobolectricTestRunner::class)
class ShareOutServiceTest : HarnessTestBase() {
    private lateinit var harness: EntriesHarness
    private lateinit var service: ShareOutService
    private lateinit var io: CoroutineDispatcher
    private val launched = CopyOnWriteArrayList<Intent>()
    private val fetched = CopyOnWriteArrayList<String>()
    private var fetch: suspend (String) -> Unit = { objectId: String -> fetched += objectId }

    private val shareDirectory: File get() = File(context.cacheDir, PickerConstants.SHARE_DIRECTORY)

    @Before
    fun registerProvider() {
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            grantUriPermissions = true
            exported = false
        }
        Robolectric.buildContentProvider(FileProvider::class.java).create(info)
    }

    private fun shareTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            io = UnconfinedTestDispatcher(testScheduler)
            harness = EntriesHarness(context, sodium, createAccountFixture(), io)
            service = DefaultShareOutService(
                ShareOutDeps(
                    context = context,
                    authority = AUTHORITY,
                    sodium = sodium,
                    rowStore = harness.fixture.rowStore,
                    files = FileStoreOriginalFiles { harness.fixture.fileStore },
                    entryKeys = harness.entryKeys,
                    readMetadata = { entryId: String -> harness.service.readMetadata(entryId) },
                    fetchOriginal = { objectId: String -> fetch(objectId) },
                    launcher = ShareLauncher { chooser: Intent -> launched += chooser },
                    now = { harness.clock.now() },
                    scope = backgroundScope,
                    ioDispatcher = io,
                ),
            )
            body()
        }

    private fun sharedFiles(): List<File> = shareDirectory.listFiles()?.filter { it.isFile }.orEmpty()

    private fun inner(chooser: Intent): Intent {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        return chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
    }

    private fun streams(send: Intent): List<Uri> =
        if (send.action == Intent.ACTION_SEND) {
            listOf(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!)
        } else {
            send.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)!!.toList()
        }

    private fun bytesAt(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun displayNameAt(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }

    private suspend fun everyItem(vararg entryIds: String): Pair<List<SharePickItem>, Set<String>> {
        val items = service.items(entryIds.toList())
        return items to items.map { it.media.mediaId }.toSet()
    }

    private fun moveOriginalToServer(mediaId: String): Pair<ObjectRow, ByteArray> {
        val row = harness.originalRow(mediaId)
        val stored = harness.fixture.fileStore.readRange(row.id, 0, row.sizeBytes.toInt())
        harness.fixture.fileStore.delete(row.id)
        harness.putObject(row.copy(localState = ObjectLocalState.MISSING, uploadState = ObjectUploadState.UPLOADED))
        return row to stored
    }

    private fun descriptor(mediaId: String, bytes: Long, mimeType: String = "image/jpeg", caption: String? = null, fileName: String? = null): MediaDescriptorV1 =
        MediaDescriptorV1(
            mediaId = mediaId,
            kind = if (mimeType.startsWith("video/")) MediaKind.VIDEO else MediaKind.PHOTO,
            mimeType = mimeType,
            width = 10,
            height = 10,
            durationMs = null,
            originalPlaintextBytes = bytes,
            thumbnail = ThumbnailDescriptor(10, 10, 10),
            capturedAtUtc = null,
            originalFileName = fileName,
            caption = caption,
        )

    @Test
    fun sharesAStoredOriginalDecryptedToItsExactBytesWithItsMimeTypeThroughTheFileProvider() = shareTest {
        val photo = patternBytes(1_500_000, 9)
        val entryId = harness.createEntry(listOf(photo))
        val (items, chosen) = everyItem(entryId)

        val result: ShareOutResult = service.share(items, chosen, ShareTextOption.NONE)

        assertEquals(ShareOutResult(shared = 1, missing = 0), result)
        val send = inner(launched.single())
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        val uri = streams(send).single()
        assertEquals("content", uri.scheme)
        assertEquals(AUTHORITY, uri.authority)
        assertArrayEquals(photo, bytesAt(uri))
        assertEquals(HARNESS_PHOTO_NAME, displayNameAt(uri))
        val file = sharedFiles().single()
        assertEquals(shareDirectory.canonicalFile, file.canonicalFile.parentFile)
        assertArrayEquals(photo, file.readBytes())
    }

    @Test
    fun updatesTheOriginalObjectRowLastAccessTime() = shareTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000)))
        val (items, chosen) = everyItem(entryId)
        harness.clock.advance(5_000)

        service.share(items, chosen, ShareTextOption.NONE)

        assertEquals(harness.clock.now(), harness.originalRow(items.single().media.mediaId).lastAccessAt)
    }

    @Test
    fun releasesTheDecryptedFilesSixtySecondsAfterSharing() = shareTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, 1), fakeJpeg(1000, 2)))
        val (items, chosen) = everyItem(entryId)

        service.share(items, chosen, ShareTextOption.NONE)
        assertEquals(2, sharedFiles().size)

        advanceTimeBy(RELEASE_MS - 1)
        runCurrent()
        assertEquals(2, sharedFiles().size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(emptyList<File>(), sharedFiles())
        assertEquals(60_000L, ShareOutConstants.RELEASE_DELAY_MS)
    }

    @Test
    fun eachShareReleasesOnlyItsOwnFilesOnItsOwnTimer() = shareTest {
        val first = harness.createEntry(listOf(fakeJpeg(1000, 1)))
        val second = harness.createEntry(listOf(fakeJpeg(1000, 2)))
        val (firstItems, firstChosen) = everyItem(first)
        val (secondItems, secondChosen) = everyItem(second)

        service.share(firstItems, firstChosen, ShareTextOption.NONE)
        advanceTimeBy(30_000)
        runCurrent()
        service.share(secondItems, secondChosen, ShareTextOption.NONE)
        assertEquals(2, sharedFiles().size)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, sharedFiles().size)
        assertArrayEquals(fakeJpeg(1000, 2), sharedFiles().single().readBytes())

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(emptyList<File>(), sharedFiles())
    }

    @Test
    fun releaseAllOnLockDeletesEveryDecryptedFileAtOnceAndLaterTimersAreHarmless() = shareTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, 1), fakeJpeg(1000, 2)))
        val (items, chosen) = everyItem(entryId)
        service.share(items, chosen, ShareTextOption.NONE)
        assertEquals(2, sharedFiles().size)

        service.releaseAll()
        assertEquals(emptyList<File>(), sharedFiles())

        advanceTimeBy(RELEASE_MS)
        runCurrent()
        assertEquals(emptyList<File>(), sharedFiles())

        service.share(items, chosen, ShareTextOption.NONE)
        assertEquals(2, sharedFiles().size)
        assertEquals(2, launched.size)
    }

    @Test
    fun skipsAnOriginalThatIsNotOnTheDeviceAndNotUploadedWithoutFetching() = shareTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, 1), fakeJpeg(1000, 2)))
        val (items, chosen) = everyItem(entryId)
        val missing = harness.originalRow(items[0].media.mediaId)
        harness.putObject(missing.copy(localState = ObjectLocalState.MISSING))

        val result: ShareOutResult = service.share(items, chosen, ShareTextOption.NONE)

        assertEquals(ShareOutResult(shared = 1, missing = 1), result)
        assertEquals(emptyList<String>(), fetched.toList())
        val send = inner(launched.single())
        assertEquals(Intent.ACTION_SEND, send.action)
        assertArrayEquals(fakeJpeg(1000, 2), bytesAt(streams(send).single()))
    }

    @Test
    fun nothingIsLaunchedWhenEverySelectedOriginalIsMissing() = shareTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, 1)))
        val (items, chosen) = everyItem(entryId)
        val row = harness.originalRow(items.single().media.mediaId)
        harness.putObject(row.copy(localState = ObjectLocalState.UNAVAILABLE, uploadState = ObjectUploadState.UPLOADED))

        val result: ShareOutResult = service.share(items, chosen, ShareTextOption.CAPTIONS_AND_NOTE)

        assertEquals(ShareOutResult(shared = 0, missing = 1), result)
        assertEquals(emptyList<Intent>(), launched.toList())
        assertEquals(emptyList<String>(), fetched.toList())
        assertEquals(emptyList<File>(), sharedFiles())
    }

    @Test
    fun downloadsAMissingUploadedOriginalAndSharesTheOriginalBytes() = shareTest {
        val photo = patternBytes(5000, 4)
        val entryId = harness.createEntry(listOf(photo))
        val (items, chosen) = everyItem(entryId)
        val (row, stored) = moveOriginalToServer(items.single().media.mediaId)
        fetch = { objectId: String ->
            fetched += objectId
            harness.fixture.fileStore.writeObject(objectId, sequenceOf(stored))
            harness.putObject(row.copy(localState = ObjectLocalState.PRESENT, uploadState = ObjectUploadState.UPLOADED))
        }

        val result: ShareOutResult = service.share(items, chosen, ShareTextOption.NONE)

        assertEquals(ShareOutResult(shared = 1, missing = 0), result)
        assertEquals(listOf(row.id), fetched.toList())
        assertArrayEquals(photo, bytesAt(streams(inner(launched.single())).single()))
    }

    @Test
    fun anOriginalStillMissingAfterTheDownloadIsSkipped() = shareTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, 1)))
        val (items, chosen) = everyItem(entryId)
        val (row, _) = moveOriginalToServer(items.single().media.mediaId)

        val result: ShareOutResult = service.share(items, chosen, ShareTextOption.NONE)

        assertEquals(ShareOutResult(shared = 0, missing = 1), result)
        assertEquals(listOf(row.id), fetched.toList())
        assertEquals(emptyList<Intent>(), launched.toList())
    }

    @Test
    fun aFailedDownloadFailsTheShareAndStillReleasesTheFilesDecryptedBeforeIt() = shareTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, 1), fakeJpeg(1000, 2)))
        val (items, chosen) = everyItem(entryId)
        moveOriginalToServer(items[1].media.mediaId)
        fetch = { objectId: String ->
            fetched += objectId
            error("download failed")
        }

        val failure = failureOf { service.share(items, chosen, ShareTextOption.NONE) }

        assertEquals("download failed", failure?.message)
        assertEquals(emptyList<Intent>(), launched.toList())
        assertEquals(1, sharedFiles().size)
        advanceTimeBy(RELEASE_MS)
        runCurrent()
        assertEquals(emptyList<File>(), sharedFiles())
    }

    @Test
    fun aDamagedOriginalFailsTheShareLeavesNoPartialFileAndReleasesTheEarlierOnes() = shareTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, 1), fakeJpeg(1000, 2)))
        val (items, chosen) = everyItem(entryId)
        val damaged = harness.originalRow(items[1].media.mediaId)
        val bytes = harness.fixture.fileStore.readRange(damaged.id, 0, damaged.sizeBytes.toInt())
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        harness.fixture.fileStore.delete(damaged.id)
        harness.fixture.fileStore.writeObject(damaged.id, sequenceOf(bytes))

        val failure = failureOf { service.share(items, chosen, ShareTextOption.NONE) }

        assertNotNull(failure)
        assertEquals(emptyList<Intent>(), launched.toList())
        val left = sharedFiles()
        assertEquals(1, left.size)
        assertArrayEquals(fakeJpeg(1000, 1), left.single().readBytes())
        advanceTimeBy(RELEASE_MS)
        runCurrent()
        assertEquals(emptyList<File>(), sharedFiles())
    }

    @Test
    fun aLockedAccountFailsTheShareWithoutLaunching() = shareTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, 1)))
        val (items, chosen) = everyItem(entryId)
        harness.entryKeys.clear()
        harness.locked = true

        assertNotNull(failureOf { service.share(items, chosen, ShareTextOption.NONE) })
        assertEquals(emptyList<Intent>(), launched.toList())
        assertEquals(emptyList<File>(), sharedFiles())
    }

    @Test
    fun severalItemsAreSentTogetherInPickerOrderWithOnlyTheCheckedOnes() = shareTest {
        val first = harness.createEntry(listOf(fakeJpeg(1000, 1), fakeJpeg(1000, 2), fakeJpeg(1000, 3)), note = "beach")
        val second = harness.createEntry(listOf(fakeJpeg(1000, 4)), note = "dinner", fileNames = listOf(null))
        val items = service.items(listOf(first, second))
        val chosen = setOf(items[0].media.mediaId, items[2].media.mediaId, items[3].media.mediaId)

        val result: ShareOutResult = service.share(items, chosen, ShareTextOption.NONE)

        assertEquals(ShareOutResult(shared = 3, missing = 0), result)
        val send = inner(launched.single())
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        val uris = streams(send)
        assertEquals(3, uris.size)
        assertEquals(listOf(fakeJpeg(1000, 1), fakeJpeg(1000, 3), fakeJpeg(1000, 4)).map { it.toList() }, uris.map { bytesAt(it).toList() })
        assertEquals(listOf(HARNESS_PHOTO_NAME, HARNESS_PHOTO_NAME, "kodakiko-1.jpg"), uris.map { displayNameAt(it) })
        assertEquals(3, send.clipData!!.itemCount)
        assertEquals(3, sharedFiles().size)
    }

    @Test
    fun theTextOptionAddsCaptionsAndTheDistinctNotesOfTheCheckedEntries() = shareTest {
        val first = harness.createEntry(listOf(fakeJpeg(1000, 1), fakeJpeg(1000, 2)), note = "  Beach day\n")
        val second = harness.createEntry(listOf(fakeJpeg(1000, 3)), note = "Dinner")
        val firstMedia = harness.service.readMetadata(first).media
        harness.service.edit(first, EntryEdit(captions = mapOf(firstMedia[0].mediaId to "Dock", firstMedia[1].mediaId to "Gulls")))
        val items = service.items(listOf(first, second))
        val all = items.map { it.media.mediaId }.toSet()

        service.share(items, all, ShareTextOption.CAPTIONS_AND_NOTE)
        service.share(items, all, ShareTextOption.CAPTIONS)
        service.share(items, setOf(items[2].media.mediaId), ShareTextOption.CAPTIONS)
        service.share(items, setOf(items[1].media.mediaId), ShareTextOption.CAPTIONS_AND_NOTE)
        service.share(items, all, ShareTextOption.NONE)

        val texts = launched.map { inner(it).getStringExtra(Intent.EXTRA_TEXT) }
        assertEquals(
            listOf(
                "Dock\nGulls\n\nBeach day\n\n\nDinner",
                "Dock\nGulls",
                null,
                "Gulls\n\nBeach day",
                null,
            ),
            texts,
        )
    }

    @Test
    fun itemsListEveryMediaOfTheEntriesInOrderWithPositionAndNote() = shareTest {
        val first = harness.createEntry(listOf(fakeJpeg(1000, 1), fakeJpeg(2000, 2)), note = "first note")
        val second = harness.createEntry(listOf(fakeJpeg(3000, 3)), note = "second note")
        val firstMeta: EntryMetadataV1 = harness.service.readMetadata(first)
        val secondMeta: EntryMetadataV1 = harness.service.readMetadata(second)

        val items: List<SharePickItem> = service.items(listOf(second, first))

        assertEquals(
            listOf(
                SharePickItem(second, secondMeta.media[0], 0, "second note"),
                SharePickItem(first, firstMeta.media[0], 0, "first note"),
                SharePickItem(first, firstMeta.media[1], 1, "first note"),
            ),
            items,
        )
    }

    @Test
    fun itemsFailWhenAnEntryCannotBeRead() = shareTest {
        val first = harness.createEntry(listOf(fakeJpeg(1000, 1)))
        assertNotNull(failureOf { service.items(listOf(first, "missing-entry-id-00000")) })
    }

    @Test
    fun theSelectionCountsTheCheckedItemsAndWarnsAboveTheShareSizeLimit() = shareTest {
        val items = listOf(
            SharePickItem("e1", descriptor("a", 200_000_000), 0, ""),
            SharePickItem("e1", descriptor("b", 250_000_000), 1, ""),
            SharePickItem("e2", descriptor("c", 100_000_000, caption = "Gulls"), 0, ""),
        )

        assertEquals(ShareSelection(count = 2, bytes = 450_000_000, warn = false), service.selection(items, setOf("a", "b")))
        assertEquals(ShareSelection(count = 3, bytes = 550_000_000, warn = true), service.selection(items, setOf("a", "b", "c")))
        assertEquals(ShareSelection(count = 0, bytes = 0, warn = false), service.selection(items, emptySet()))
        val edge = listOf(SharePickItem("e", descriptor("x", SharingConstants.SHARE_WARN_BYTES), 0, ""))
        assertFalse(service.selection(edge, setOf("x")).warn)
        assertTrue(service.selection(listOf(SharePickItem("e", descriptor("x", SharingConstants.SHARE_WARN_BYTES + 1), 0, "")), setOf("x")).warn)
    }

    @Test
    fun anEmptySelectionLaunchesNothing() = shareTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, 1)))
        val items = service.items(listOf(entryId))

        assertEquals(ShareOutResult(shared = 0, missing = 0), service.share(items, emptySet(), ShareTextOption.CAPTIONS_AND_NOTE))
        assertEquals(emptyList<Intent>(), launched.toList())
    }

    @Test
    fun aVideoIsSentWithItsOwnMimeType() = shareTest {
        val video = harness.createEntry(listOf(tinyMp4()), fileNames = listOf("clip.mp4"))
        val (items, chosen) = everyItem(video)

        service.share(items, chosen, ShareTextOption.NONE)

        val send = inner(launched.single())
        assertEquals("video/mp4", send.type)
        assertEquals("clip.mp4", displayNameAt(streams(send).single()))
        assertArrayEquals(tinyMp4(), bytesAt(streams(send).single()))
    }

    @Test
    fun theSharedFileNameIsTheOriginalNameOrANumberedNameByMimeType() {
        assertEquals("beach.jpg", shareFileName(descriptor("m", 1, fileName = "beach.jpg"), 4))
        val expected = mapOf(
            "image/jpeg" to "kodakiko-3.jpg",
            "image/png" to "kodakiko-3.png",
            "image/webp" to "kodakiko-3.webp",
            "video/mp4" to "kodakiko-3.mp4",
            "video/webm" to "kodakiko-3.webm",
            "audio/mp4" to "kodakiko-3.m4a",
            "audio/webm" to "kodakiko-3.weba",
            "image/heic" to "kodakiko-3.bin",
            "video/quicktime" to "kodakiko-3.bin",
            "application/octet-stream" to "kodakiko-3.bin",
        )
        for ((mimeType, name) in expected) assertEquals(mimeType, name, shareFileName(descriptor("m", 1, mimeType = mimeType), 2))
        assertEquals("kodakiko-1.jpg", shareFileName(descriptor("m", 1), 0))
    }
}

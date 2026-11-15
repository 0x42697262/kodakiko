@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.links

import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.Routes
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.data.upload.FailureReason
import dev.kodakiko.data.upload.UploadEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

private const val UNDECODABLE_SIZE = 2222

private fun reencoderRefusing(size: Int): PhotoReencoder =
    PhotoReencoder { bytes: ByteArray, _: String ->
        if (bytes.size == size) throw IOException("undecodable")
        ReencodedPhoto(bytes.copyOf(), 100, 100)
    }

@RunWith(RobolectricTestRunner::class)
class PublicLinksServiceAdditionsTest : LinksTestBase() {
    @Test
    fun progressReportsEveryStepWithByteCountsAfterFetchingTheSourceObjects() = linksTest {
        val first = syncedEntry(listOf(fakeJpeg(2000, 1)))
        val second = syncedEntry(listOf(fakeJpeg(2500, 2)))
        val firstMedia = harness.service.readMetadata(first).media[0].mediaId
        val secondMedia = harness.service.readMetadata(second).media[0].mediaId
        uploader.eventsFor = { job -> listOf(UploadEvent.Progress(job.objectId, 10), UploadEvent.Completed(job.objectId)) }
        val progress = mutableListOf<CreateLinkProgress>()
        service.create(input(entryIds = listOf(first, second), mediaIds = setOf(firstMedia, secondMedia), title = "  Picnic \n")) { step: CreateLinkProgress ->
            progress += step
        }
        val total = presignRequests().sumOf { it.sizeBytes }
        val uploading = CreateLinkStep.UPLOADING
        assertEquals(
            listOf(
                CreateLinkProgress(CreateLinkStep.PREPARING, 0, 2, 0, 0),
                CreateLinkProgress(CreateLinkStep.PREPARING, 1, 2, 0, 0),
                CreateLinkProgress(uploading, 0, 4, 0, total),
                CreateLinkProgress(uploading, 0, 4, 10, total),
                CreateLinkProgress(uploading, 0, 4, 20, total),
                CreateLinkProgress(uploading, 0, 4, 30, total),
                CreateLinkProgress(uploading, 0, 4, 40, total),
                CreateLinkProgress(uploading, 0, 4, total, total),
                CreateLinkProgress(CreateLinkStep.REGISTERING, 0, 1, total, total),
            ),
            progress,
        )
        val sourceObjects = listOf(firstMedia, secondMedia).flatMap { mediaId ->
            val rows = harness.objectRowsOfMedia(mediaId)
            listOf(rows.first { it.variant == ObjectVariant.ORIGINAL }.id, rows.first { it.variant == ObjectVariant.THUMBNAIL }.id)
        }
        assertEquals(sourceObjects, fetched.toList())
        val collection = collectionRequest()
        val linkKey = linkKeyOf(linkSecretOf(collection))
        assertEquals("Picnic", openCollectionTitle(linkKey, collection.id, collection.metadataBlob))
        val copies = entryRequests()
        assertEquals(2, copies.size)
        val firstCopy = openCopyMetadata(copyEntryKey(linkKey, collection.id, copies[0]), copies[0].id, copies[0].metadataBlob)
        assertEquals(100L, firstCopy.media[0].width)
        assertEquals(100L, firstCopy.media[0].height)
        assertEquals(2000L, firstCopy.media[0].originalPlaintextBytes)
        assertEquals("image/jpeg", firstCopy.media[0].mimeType)
    }

    @Test
    fun invalidInputIsRefusedBeforeContactingTheServer() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(2000, 1)))
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        val trashed = syncedEntry(listOf(fakeJpeg(2000, 2)))
        val trashedMedia = harness.service.readMetadata(trashed).media[0].mediaId
        harness.fixture.rowStore.transaction { tx -> tx.entries.get(trashed)?.let { tx.entries.put(it.copy(trashedAt = NOW)) } }
        suspend fun reasonOf(linkInput: CreateLinkInput): LinkInputReason = expectFailure<LinkInputException> { service.create(linkInput) {} }.reason
        signedIn = false
        assertEquals(LinkInputReason.LOCKED, reasonOf(input(listOf(entryId), setOf(mediaId))))
        signedIn = true
        assertEquals(LinkInputReason.ENTRIES, reasonOf(input(emptyList(), emptySet())))
        assertEquals(LinkInputReason.ENTRIES, reasonOf(input(List(LinksConstants.MAX_ENTRIES + 1) { entryId }, setOf(mediaId))))
        assertEquals(LinkInputReason.MEDIA, reasonOf(input(listOf(entryId), emptySet())))
        assertEquals(LinkInputReason.TITLE, reasonOf(input(listOf(entryId), setOf(mediaId), title = " \n ")))
        assertEquals(LinkInputReason.TITLE, reasonOf(input(listOf(entryId), setOf(mediaId), title = "a".repeat(101))))
        assertEquals(LinkInputReason.ENTRY, reasonOf(input(listOf(entryId, trashed), setOf(mediaId, trashedMedia))))
        assertEquals(LinkInputReason.ENTRY, reasonOf(input(listOf("Z".repeat(22)), setOf(mediaId))))
        assertEquals(0, fake.requests.size)
        assertEquals(100, LinksConstants.MAX_ENTRIES)
    }

    @Test
    fun undecodablePhotosAndUnsupportedVideosAreSkippedAndCounted() = linksTest {
        val undecodable = syncedEntry(listOf(fakeJpeg(UNDECODABLE_SIZE, 9)))
        val mixed = syncedEntry(listOf(fragmentedMp4(), fakeJpeg(2000, 3)))
        val media = (harness.service.readMetadata(undecodable).media + harness.service.readMetadata(mixed).media).map { it.mediaId }.toSet()
        val before = localObjectIds()
        val skipping = createService(DefaultLinkStripper(reencoderRefusing(UNDECODABLE_SIZE)))
        val result: CreateLinkResult = skipping.create(input(listOf(undecodable, mixed), media)) {}
        assertEquals(1, result.skippedEntries)
        assertEquals(1, result.skippedUndecodable)
        assertEquals(1, result.skippedUnsupportedVideo)
        val copies = entryRequests()
        assertEquals(1, copies.size)
        assertEquals(1, copies[0].media.size)
        assertEquals(2, presignRequests().size)
        assertEquals(before, localObjectIds())
    }

    @Test
    fun nothingLeftToLinkRemovesTheCollection() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(UNDECODABLE_SIZE, 9)))
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        val skipping = createService(DefaultLinkStripper(reencoderRefusing(UNDECODABLE_SIZE)))
        assertEquals(LinkInputReason.NOTHING, expectFailure<LinkInputException> { skipping.create(input(listOf(entryId), setOf(mediaId))) {} }.reason)
        val collection = collectionRequest()
        assertEquals(1, requestsOf(DELETE, Routes.collection(collection.id)).size)
        assertEquals(0, entryRequests().size)
        assertEquals(0, presignRequests().size)
        assertEquals(0, requestsOf(POST, Routes.publicLinks()).size)
    }

    @Test
    fun aFailedUploadRemovesTheCollectionAndTheLocalCopies() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(2000, 1)))
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        val before = localObjectIds()
        uploader.eventsFor = { job -> listOf(UploadEvent.Failed(job.objectId, FailureReason.HTTP)) }
        val failure = expectFailure<LinkUploadFailedException> { service.create(input(listOf(entryId), setOf(mediaId))) {} }
        assertEquals(FailureReason.HTTP, failure.reason)
        assertEquals(1, requestsOf(DELETE, Routes.collection(collectionRequest().id)).size)
        assertEquals(0, fake.count(POST, CONFIRM_PATTERN))
        assertEquals(0, requestsOf(POST, Routes.publicLinks()).size)
        assertEquals(before, localObjectIds())
        assertEquals(0, uploader.listenerCount())
    }

    @Test
    fun cancellingWhileUploadingRemovesTheCollectionAndTheLocalCopies() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(2000, 1)))
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        val before = localObjectIds()
        uploader.eventsFor = { emptyList() }
        val job = launch { service.create(input(listOf(entryId), setOf(mediaId))) {} }
        advanceUntilIdle()
        assertEquals(2, uploader.jobs.size)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, requestsOf(DELETE, Routes.collection(collectionRequest().id)).size)
        assertEquals(0, fake.count(POST, CONFIRM_PATTERN))
        assertEquals(0, requestsOf(POST, Routes.publicLinks()).size)
        assertEquals(before, localObjectIds())
        assertEquals(0, uploader.listenerCount())
    }

    @Test
    fun aRegistrationFailureRemovesTheCollection() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(2000, 1)))
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        val before = localObjectIds()
        fake.on(POST, Routes.publicLinks()) { throw apiFailure(500, ErrorCode.INTERNAL) }
        assertEquals(500, expectFailure<ApiError> { service.create(input(listOf(entryId), setOf(mediaId))) {} }.status)
        assertEquals(1, requestsOf(DELETE, Routes.collection(collectionRequest().id)).size)
        assertEquals(before, localObjectIds())
    }

    @Test
    fun aFailedCollectionCreationSendsNoDelete() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(2000, 1)))
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        fake.on(POST, Routes.collections()) { throw NetworkError() }
        expectFailure<NetworkError> { service.create(input(listOf(entryId), setOf(mediaId))) {} }
        assertEquals(0, fake.count(DELETE, COLLECTION_PATTERN))
        assertEquals(0, entryRequests().size)
    }

    @Test
    fun missingObjectRowsAbortTheLink() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(2000, 1)))
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        harness.fixture.rowStore.transaction { tx -> tx.objects.byMedia(mediaId).filter { it.variant == ObjectVariant.THUMBNAIL }.forEach { tx.objects.delete(it.id) } }
        assertEquals(LinkInputReason.OBJECTS, expectFailure<LinkInputException> { service.create(input(listOf(entryId), setOf(mediaId))) {} }.reason)
        assertEquals(1, requestsOf(DELETE, Routes.collection(collectionRequest().id)).size)
        assertEquals(0, entryRequests().size)
    }

    @Test
    fun revokeDeletesThePublicLinkEvenWhileLocked() = linksTest {
        fake.on(DELETE, Routes.publicLink(LINK_ID)) { null }
        signedIn = false
        service.revoke(LINK_ID)
        assertEquals(1, fake.count(DELETE, Routes.publicLink(LINK_ID)))
    }

    @Test
    fun listRequiresAnUnlockedAccount() = linksTest {
        signedIn = false
        assertEquals(LinkInputReason.LOCKED, expectFailure<LinkInputException> { service.list() }.reason)
        assertEquals(0, fake.requests.size)
    }
}

class LinksConstantsTest {
    @Test
    fun linkConstantsMatchVersion031() {
        assertEquals(100, LinksConstants.MAX_ENTRIES)
        assertEquals(32, LinksConstants.LINK_SECRET_BYTES)
        assertEquals("/s/", LinksConstants.LINK_PATH_PREFIX)
        assertEquals(1600, LinksConstants.PHOTO_MAX_EDGE)
        assertEquals(85, LinksConstants.PHOTO_QUALITY)
        assertEquals("image/jpeg", LinksConstants.PHOTO_MIME)
    }
}

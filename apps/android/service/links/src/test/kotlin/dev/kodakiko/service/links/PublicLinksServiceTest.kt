@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.links

import dev.kodakiko.contract.CollectionDto
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.EntryPurpose
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.PublicLinkDto
import dev.kodakiko.contract.PublicLinkListResponse
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.KdfContext
import dev.kodakiko.crypto.KdfSubkeyIds
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.media.ByteSource
import dev.kodakiko.media.byteSourceFromBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import dev.kodakiko.contract.CollectionKind as WireCollectionKind
import dev.kodakiko.contract.CollectionRole as WireCollectionRole
import dev.kodakiko.contract.ObjectVariant as WireObjectVariant

private val ID_SEGMENT = Regex("[A-Za-z0-9_-]{22}")

@RunWith(RobolectricTestRunner::class)
class PublicLinksServiceTest : LinksTestBase() {
    @Test
    fun createsALinkCopyWithOnlyTheSelectedMediaNoLocationOrTagsAndReEncryptedThumbnails() = linksTest {
        val photo = fakeJpeg(3000, 4)
        val video = mp4WithLocation()
        val first = syncedEntry(listOf(photo, video))
        val second = syncedEntry(listOf(fakeJpeg(2000, 6)))
        val firstMeta = harness.service.readMetadata(first)
        val secondMeta = harness.service.readMetadata(second)
        val selected = setOf(firstMeta.media[0].mediaId, firstMeta.media[1].mediaId)
        val progress = mutableListOf<CreateLinkStep>()

        val result: CreateLinkResult = service.create(
            input(entryIds = listOf(first, second), mediaIds = selected, title = "Picnic", expiry = LinkExpiry.SEVEN_DAYS, includeNotes = false),
        ) { update: CreateLinkProgress -> progress += update.step }

        assertEquals(LINK_ID, result.linkId)
        val fragment = parseLinkFragment(fragmentOf(result.url))
        assertNotNull(fragment)
        assertTrue(result.url.startsWith("$BASE_URL/s/$LINK_ID#"))
        assertEquals(0, result.skippedEntries)
        assertEquals(0, result.skippedUndecodable)
        assertEquals(0, result.skippedUnsupportedVideo)
        assertEquals(setOf(CreateLinkStep.PREPARING, CreateLinkStep.UPLOADING, CreateLinkStep.REGISTERING), progress.toSet())

        val order = fake.requests.map { "${it.method} ${it.path.replace(ID_SEGMENT, ":id")}" }
        assertEquals("$POST ${Routes.collections()}", order.first())
        assertEquals("$POST ${Routes.publicLinks()}", order.last())

        val collection = collectionRequest()
        assertEquals(WireCollectionKind.LINK, collection.kind)
        val secret = linkSecretOf(collection)
        assertArrayEquals(fragment, secret)
        val linkKey = linkKeyOf(secret)
        val accessToken = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.LINK_ACCESS_TOKEN, KdfContext.LINK.value, secret)
        assertEquals("Picnic", openCollectionTitle(linkKey, collection.id, collection.metadataBlob))
        val registered = ContractSchemas.publicLinkCreateRequest.decode(requestsOf(POST, Routes.publicLinks()).first().json())
        assertEquals(encodeBytes(accessToken), registered.accessToken)
        assertEquals(collection.id, registered.collectionId)
        assertEquals(LinkExpiry.SEVEN_DAYS, registered.expiry)

        val entries = entryRequests()
        assertEquals(1, entries.size)
        val copy = entries[0]
        assertEquals(EntryPurpose.LINK_COPY, copy.purpose)
        assertEquals(listOf(collection.id), copy.memberships.map { it.collectionId })
        val entryKey = copyEntryKey(linkKey, collection.id, copy)
        val metadata = openCopyMetadata(entryKey, copy.id, copy.metadataBlob)
        assertNull(metadata.location)
        assertEquals(emptyList<String>(), metadata.tags)
        assertEquals("", metadata.note)
        assertEquals(firstMeta.takenAtUtc, metadata.takenAtUtc)
        assertEquals(firstMeta.utcOffsetMinutes, metadata.utcOffsetMinutes)
        assertNull(metadata.timeZone)
        assertEquals(harness.clock.nowIso(), metadata.createdAtUtc)
        assertEquals(harness.clock.nowIso(), metadata.editedAtUtc)
        assertEquals(listOf("image/jpeg", "video/mp4"), metadata.media.map { it.mimeType })
        assertTrue(metadata.media.all { it.capturedAtUtc == null && it.originalFileName == null })
        assertTrue(metadata.media.none { it.mediaId in selected })
        assertEquals(metadata.media.map { it.mediaId }, copy.media.map { it.mediaId })
        assertEquals(1, secondMeta.media.size)

        val presigns = presignRequests()
        assertEquals(4, presigns.size)
        val sourceThumbnail = harness.thumbnailPlaintext(first, firstMeta.media[0].mediaId)
        metadata.media.forEachIndexed { index: Int, media: MediaDescriptorV1 ->
            val wrapped = copy.media.first { it.mediaId == media.mediaId }.mediaKeyByEntry
            val mediaKey = unwrapKey(sodium, entryKey, decodeBytes(wrapped), AdContext.MEDIA_KEY_BY_ENTRY, "${copy.id}/${media.mediaId}")
            val thumbKey = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.MEDIA_THUMBNAIL, KdfContext.MEDIA.value, mediaKey)
            val originalKey = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.MEDIA_ORIGINAL, KdfContext.MEDIA.value, mediaKey)
            val thumbObject = presigns.first { it.mediaId == media.mediaId && it.variant == WireObjectVariant.THUMBNAIL }
            val originalObject = presigns.first { it.mediaId == media.mediaId && it.variant == WireObjectVariant.ORIGINAL }
            assertArrayEquals(sourceThumbnail, decryptUploaded(sodium, uploader.uploaded, thumbObject.id, thumbKey, media.thumbnail.plaintextBytes))
            val plain = decryptUploaded(sodium, uploader.uploaded, originalObject.id, originalKey, media.originalPlaintextBytes)
            if (index == 0) assertArrayEquals(photo, plain) else assertTrue(plain.size < video.size)
            assertEquals(uploader.uploaded.getValue(thumbObject.id).size.toLong(), thumbObject.sizeBytes)
            assertEquals(uploader.uploaded.getValue(originalObject.id).size.toLong(), originalObject.sizeBytes)
        }
        assertEquals(4, fake.count(POST, CONFIRM_PATTERN))
        for (id in uploader.uploaded.keys) assertNull(harness.fixture.fileStore.size(id))
        assertEquals(0, uploader.listenerCount())
    }

    @Test
    fun keepsTheNoteWhenIncludeNotesIsOn() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(2000, 2)), "see you **soon**")
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        service.create(input(entryIds = listOf(entryId), mediaIds = setOf(mediaId), title = "Note", expiry = LinkExpiry.ONE_DAY, includeNotes = true)) {}
        val copy = entryRequests().first()
        val collection = collectionRequest()
        val linkKey = linkKeyOf(linkSecretOf(collection))
        val entryKey = copyEntryKey(linkKey, collection.id, copy)
        assertEquals("see you **soon**", openCopyMetadata(entryKey, copy.id, copy.metadataBlob).note)
    }

    @Test
    fun stopsAndDeletesTheLinkCollectionWhenTheStripperVerificationFails() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(2000, 3)))
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        val failing = createService(
            object : LinkStripper {
                override suspend fun strip(source: ByteSource, descriptor: MediaDescriptorV1): StrippedMedia = throw StripFailedException()
            },
        )
        expectFailure<StripFailedException> { failing.create(input(entryIds = listOf(entryId), mediaIds = setOf(mediaId))) {} }
        val collection = collectionRequest()
        assertEquals(1, requestsOf(DELETE, Routes.collection(collection.id)).size)
        assertEquals(0, requestsOf(POST, Routes.publicLinks()).size)
    }

    @Test
    fun createsALinkForAnEntryThatIsStillUploadingFromTheLocalCopies() = linksTest {
        val pending = harness.createEntry(listOf(fakeJpeg(2000, 7)))
        val mediaId = harness.service.readMetadata(pending).media[0].mediaId
        val created: CreateLinkResult = service.create(input(entryIds = listOf(pending), mediaIds = setOf(mediaId))) {}
        assertTrue(created.url.contains("#"))
    }

    @Test
    fun refusesMediaThatDoNotBelongToTheEntries() = linksTest {
        val synced = syncedEntry(listOf(fakeJpeg(2000, 8)))
        val failure = expectFailure<LinkInputException> { service.create(input(entryIds = listOf(synced), mediaIds = setOf("X".repeat(22)))) {} }
        assertEquals(LinkInputReason.MEDIA, failure.reason)
        assertEquals(0, requestsOf(POST, Routes.collections()).size)
    }

    @Test
    fun listRebuildsTheExactUrlFromTheLinkCollectionKey() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(2000, 9)))
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        val created: CreateLinkResult = service.create(input(entryIds = listOf(entryId), mediaIds = setOf(mediaId), title = "Trip", expiry = LinkExpiry.ONE_VIEW)) {}
        val collection = collectionRequest()
        fake.on(GET, Routes.publicLinks()) {
            ContractSchemas.publicLinkListResponse.encode(PublicLinkListResponse(listOf(PublicLinkDto(LINK_ID, collection.id, null, 1, 0, NOW))))
        }
        fake.on(GET, Routes.collection(collection.id)) {
            ContractSchemas.collectionDto.encode(
                CollectionDto(
                    id = collection.id,
                    kind = WireCollectionKind.LINK,
                    role = WireCollectionRole.OWNER,
                    ownerId = LINK_OWNER_ID,
                    ownerUsername = "ada",
                    keyMaterial = collection.keyByOwner,
                    metadataBlob = collection.metadataBlob,
                    lastSeq = 1,
                    createdAt = NOW,
                    metaUpdatedAt = NOW,
                    recipientCount = null,
                ),
            )
        }
        val links: List<OwnerLink> = service.list()
        assertEquals(
            listOf(OwnerLink(linkId = LINK_ID, url = created.url, title = "Trip", expiresAt = null, maxViews = 1, viewCount = 0, createdAt = NOW)),
            links,
        )
    }
}

class LinkUrlTest {
    @Test
    fun roundTripsTheSecretThroughTheFragmentAndRejectsBadFragments() {
        val secret = ByteArray(32) { it.toByte() }
        val url = buildLinkUrl("https://a.example/", LINK_ID, secret)
        assertEquals("https://a.example/s/$LINK_ID#${encodeBytes(secret)}", url)
        assertArrayEquals(secret, parseLinkFragment(fragmentOf(url)))
        assertNull(parseLinkFragment(""))
        assertNull(parseLinkFragment("#short"))
        assertNull(parseLinkFragment("#!!!"))
    }
}

@RunWith(RobolectricTestRunner::class)
class DecryptingByteSourceTest : LinksTestBase() {
    @Test
    fun readsRandomRangesAcrossChunkBoundariesAsPlaintext() = linksTest {
        val plaintext = seededBytes(3 * 1_048_576 + 777, 2)
        val expected = joinBytes(fakeJpeg(10, 1).copyOfRange(0, 4), plaintext)
        val entryId = harness.createEntry(listOf(expected))
        val media = harness.service.readMetadata(entryId).media[0]
        val original = harness.objectRowsOfMedia(media.mediaId).first { it.variant == ObjectVariant.ORIGINAL }
        val key = harness.entryKeys.variantKey(entryId, media.mediaId, ObjectVariant.ORIGINAL)
        val source: DecryptingByteSource = openDecryptingByteSource(sodium, FileStoreLinkFiles { harness.fixture.fileStore }, original.id, key, original.plaintextSize)
        try {
            assertEquals(expected.size.toLong(), source.size)
            for ((offset, length) in listOf(0 to 10, 1_048_570 to 20, 2_097_100 to 1_048_700, (expected.size - 5) to 50)) {
                assertArrayEquals(expected.copyOfRange(offset, minOf(expected.size, offset + length)), source.read(offset.toLong(), length.toLong()))
            }
            assertEquals(byteSourceFromBytes(expected).size, source.size)
        } finally {
            source.close()
        }
    }
}

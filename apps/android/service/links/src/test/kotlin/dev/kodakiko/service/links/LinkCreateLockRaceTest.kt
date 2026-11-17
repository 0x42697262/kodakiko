package dev.kodakiko.service.links

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.Routes
import dev.kodakiko.crypto.wipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinkCreateLockRaceTest : LinksTestBase() {
    private fun lockLikeTheAuthService() {
        signedIn = false
        wipe(harness.fixture.keys.masterKey)
        wipe(harness.fixture.keys.privateKey)
    }

    @Test
    fun lockingAfterTheAccountCheckAndBeforeTheWrapSendsNoCollectionRequest() = linksTest {
        val entryId = syncedEntry(listOf(fakeJpeg(2000, 7)))
        val mediaId = harness.service.readMetadata(entryId).media[0].mediaId
        val lockingRead: suspend (String) -> EntryMetadataV1 = { id: String ->
            val metadata = harness.service.readMetadata(id)
            lockLikeTheAuthService()
            metadata
        }
        val racing = createService(metadata = lockingRead)

        val failure = expectFailure<LinkInputException> { racing.create(input(entryIds = listOf(entryId), mediaIds = setOf(mediaId))) {} }

        assertFalse(signedIn)
        assertEquals(LinkInputReason.LOCKED, failure.reason)
        assertEquals(emptyList<RecordedCall>(), requestsOf(POST, Routes.collections()))
        assertEquals(emptyList<RecordedCall>(), fake.requests.toList())
        assertEquals(0, uploader.jobs.size)
    }
}

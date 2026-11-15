package dev.kodakiko.service.index

import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryPurpose
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.MediaRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.index.IndexedEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexEntryRowsTest {
    private val owner = "owner"
    private val createdAt = "2026-01-31T23:59:00.000Z"

    private fun row(id: String, ownerId: String = owner, localState: EntryLocalState = EntryLocalState.SYNCED, trashedAt: String? = null) =
        EntryRow(
            id = id,
            ownerId = ownerId,
            purpose = EntryPurpose.JOURNAL,
            revision = 1,
            metadataBlob = byteArrayOf(1),
            baseMetadataBlob = null,
            baseRevision = 1,
            serverPendingBlob = null,
            serverPendingRevision = null,
            trashedAt = trashedAt,
            createdAt = createdAt,
            updatedAt = createdAt,
            localState = localState,
        )

    private fun objectRow(id: String, mediaId: String, uploadState: ObjectUploadState) =
        ObjectRow(id, mediaId, ObjectVariant.ORIGINAL, 1, 1, ObjectLocalState.PRESENT, uploadState, null, 0)

    @Test
    fun yieldsBeforeEveryTwoHundredthEntryAfterTheFirst() = runTest {
        assertEquals(200, IndexServiceConstants.INDEX_BUILD_YIELD_EVERY)
        val expected = listOf(0 to 0, 1 to 0, 200 to 0, 201 to 1, 401 to 2, 600 to 2, 601 to 3)
        for ((count, yields) in expected) {
            val trace = mutableListOf<String>()
            val counts = indexEntryRows(
                rows = IndexRows(List(count) { row("e$it") }, emptyList(), emptyList(), emptyList()),
                userId = owner,
                open = { metadataOf("", emptyList(), emptyList()) },
                accept = { entry, _ -> trace.add(entry.id) },
                yieldPoint = { trace.add(YIELD) },
            )
            assertEquals("yields for $count", yields, trace.count { it == YIELD })
            assertEquals(IndexedRowsCount(entries = count, unreadable = 0), counts)
            val positions = trace.withIndex().filter { it.value == YIELD }.map { it.index - trace.take(it.index).count { item -> item == YIELD } }
            assertEquals(List(yields) { (it + 1) * IndexServiceConstants.INDEX_BUILD_YIELD_EVERY }, positions)
        }
    }

    @Test
    fun mapsRowsAndMetadataLikeV031() = runTest {
        val video = descriptorOf("m-video", 1_000).copy(kind = MediaKind.VIDEO, durationMs = 1_500)
        val photo = descriptorOf("m-photo", 500, caption = "Sunset")
        val longNote = "a".repeat(139) + "\uD83D\uDE00" + "b"
        val metadata = metadataOf(longNote, listOf("trip"), listOf(video, photo)).copy(
            takenAtUtc = "2026-03-01T23:30:00.000Z",
            utcOffsetMinutes = 60,
            favorite = true,
        )
        val accepted = mutableListOf<Pair<IndexedEntry, String>>()
        val counts = indexEntryRows(
            rows = IndexRows(
                entries = listOf(
                    row("e-broken"),
                    row("e-shared", ownerId = "someone", trashedAt = "2026-04-01T00:00:00.000Z"),
                    row("e-queued"),
                    row("e-editing", localState = EntryLocalState.PENDING_UPDATE),
                ),
                memberships = listOf(
                    MembershipRow("c-one", "e-shared", byteArrayOf(1), 0),
                    MembershipRow("c-two", "e-shared", byteArrayOf(1), 0),
                ),
                media = listOf(
                    MediaRow("m-video", "e-shared", byteArrayOf(1)),
                    MediaRow("m-photo", "e-shared", byteArrayOf(1)),
                    MediaRow("m-queued", "e-queued", byteArrayOf(1)),
                ),
                objects = listOf(
                    objectRow("o-1", "m-video", ObjectUploadState.UPLOADED),
                    objectRow("o-2", "m-photo", ObjectUploadState.UPLOADED),
                    objectRow("o-3", "m-queued", ObjectUploadState.UPLOADED),
                    objectRow("o-4", "m-queued", ObjectUploadState.QUEUED),
                ),
            ),
            userId = owner,
            open = { entryRow ->
                if (entryRow.id == "e-broken") throw IllegalStateException("unreadable")
                metadata
            },
            accept = { entry, noteText -> accepted.add(entry to noteText) },
        )

        assertEquals(IndexedRowsCount(entries = 4, unreadable = 1), counts)
        assertEquals(
            IndexedEntry(
                id = "e-broken",
                isOwn = true,
                collectionIds = emptyList(),
                trashed = false,
                unreadable = true,
                takenAtUtc = createdAt,
                utcOffsetMinutes = 0,
                localDate = "2026-01-31",
                monthDay = "01-31",
                tags = emptyList(),
                coverMediaId = null,
                mediaCount = 0,
                hasVideo = false,
                notePreview = "",
                pending = false,
            ) to "",
            accepted[0],
        )
        assertEquals(
            IndexedEntry(
                id = "e-shared",
                isOwn = false,
                collectionIds = listOf("c-one", "c-two"),
                trashed = true,
                unreadable = false,
                takenAtUtc = "2026-03-01T23:30:00.000Z",
                utcOffsetMinutes = 60,
                localDate = "2026-03-02",
                monthDay = "03-02",
                tags = listOf("trip"),
                coverMediaId = "m-video",
                mediaCount = 2,
                hasVideo = true,
                notePreview = "a".repeat(139) + "\uD83D\uDE00",
                pending = false,
                favorite = true,
                videoCount = 1,
                videoMs = 1_500,
                originalBytes = 1_500,
            ) to "$longNote\nSunset",
            accepted[1],
        )
        assertEquals(true, accepted[2].first.pending)
        assertEquals(true, accepted[3].first.pending)
        assertEquals(listOf("e-broken", "e-shared", "e-queued", "e-editing"), accepted.map { it.first.id })
    }

    @Test
    fun anAccountLockedFailureStopsThePass() = runTest {
        val accepted = mutableListOf<String>()
        val failure = runCatching {
            indexEntryRows(
                rows = IndexRows(listOf(row("e1"), row("e2"), row("e3")), emptyList(), emptyList(), emptyList()),
                userId = owner,
                open = { entryRow ->
                    if (entryRow.id == "e2") throw AccountLockedException()
                    metadataOf("", emptyList(), emptyList())
                },
                accept = { entry, _ -> accepted.add(entry.id) },
            )
        }.exceptionOrNull()

        assertTrue(failure is AccountLockedException)
        assertEquals(listOf("e1"), accepted)
    }

    private companion object {
        const val YIELD = "yield"
    }
}

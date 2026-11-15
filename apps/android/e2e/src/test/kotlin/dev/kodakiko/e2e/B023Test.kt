package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.EntryTimeEdit
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class B023Test : E2eTestBase() {
    override val server: E2eServer get() = harness

    @Test
    fun whenTheUserEditsNoteTagsTimeLocationAndPhotoOrderThenEveryDeviceShowsTheEditAfterSync() = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(
            EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES), randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), "before", listOf("old")),
        )
        b.syncUntilIdle()
        val reordered = entry.mediaIds.reversed()

        a.entries.edit(
            entry.entryId,
            EntryEdit(
                note = EDITED_NOTE,
                tags = listOf("trip", "new"),
                time = EntryTimeEdit(EDITED_TAKEN_AT, EDITED_OFFSET_MINUTES),
                location = OptionalValue.Present(LOCATION),
                mediaOrder = reordered,
            ),
        )
        a.syncUntilIdle()
        b.syncUntilIdle()

        for (device in listOf(a, b)) {
            val metadata = device.readMetadata(entry.entryId)
            assertEquals(EDITED_NOTE, metadata.note)
            assertEquals(listOf("new", "trip"), metadata.tags)
            assertEquals(EDITED_TAKEN_AT, metadata.takenAtUtc)
            assertEquals(EDITED_OFFSET_MINUTES, metadata.utcOffsetMinutes)
            assertEquals(LOCATION, metadata.location)
            assertEquals(reordered, metadata.media.map { it.mediaId })
            assertEquals(E2eConstants.SECOND_REVISION, device.readEntry(entry.entryId)?.revision)
        }
    }

    companion object {
        private const val EDITED_NOTE = "after the edit"
        private const val EDITED_TAKEN_AT = "2025-06-01T08:30:00.000Z"
        private const val EDITED_OFFSET_MINUTES = 120L
        private const val LATITUDE = 48.8584
        private const val LONGITUDE = 2.2945
        private const val ACCURACY_METERS = 12.0
        private val LOCATION = EntryLocation(LATITUDE, LONGITUDE, ACCURACY_METERS, LocationSource.DEVICE)

        @get:ClassRule
        @JvmStatic
        val harness = E2eServer()
    }
}

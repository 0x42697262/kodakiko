package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class B024Test : E2eTestBase() {
    override val server: E2eServer get() = harness

    private suspend fun expectConverged(devices: List<E2eDevice>, entryId: String, expectedNote: String) {
        for (device in devices) {
            assertEquals(E2eConstants.THIRD_REVISION, device.readEntry(entryId)?.revision)
            val metadata = device.readMetadata(entryId)
            assertEquals(expectedNote, metadata.note)
            assertEquals(MERGED_TAGS, metadata.tags)
        }
    }

    @Test
    fun scenario2WhenBothSyncThenANoteAndBTagBothSurviveAtRevision3() = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), BASE_NOTE, listOf(BASE_TAG)))
        b.syncUntilIdle()
        a.editEntryLocally(entry.entryId, { it.copy(note = NOTE_FROM_A) }, E2eConstants.EARLIER_EDIT_UTC)
        b.editEntryLocally(entry.entryId, { it.copy(tags = it.tags + ADDED_TAG) }, E2eConstants.LATER_EDIT_UTC)

        a.syncUntilIdle()
        b.syncUntilIdle()
        a.syncUntilIdle()

        expectConverged(listOf(a, b), entry.entryId, NOTE_FROM_A)
    }

    private fun bothChangeTheNote(aEditedAt: String, bEditedAt: String, note: String) = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), BASE_NOTE, listOf(BASE_TAG)))
        b.syncUntilIdle()
        a.editEntryLocally(entry.entryId, { it.copy(note = NOTE_FROM_A) }, aEditedAt)
        b.editEntryLocally(entry.entryId, { it.copy(note = NOTE_FROM_B, tags = it.tags + ADDED_TAG) }, bEditedAt)

        a.syncUntilIdle()
        b.syncUntilIdle()
        a.syncUntilIdle()

        expectConverged(listOf(a, b), entry.entryId, note)
    }

    @Test
    fun whenBothChangeTheNoteThenTheLaterEditWinsAndTheTagsAreMergedBEditedLater() =
        bothChangeTheNote(E2eConstants.EARLIER_EDIT_UTC, E2eConstants.LATER_EDIT_UTC, NOTE_FROM_B)

    @Test
    fun whenBothChangeTheNoteThenTheLaterEditWinsAndTheTagsAreMergedAEditedLater() =
        bothChangeTheNote(E2eConstants.LATER_EDIT_UTC, E2eConstants.EARLIER_EDIT_UTC, NOTE_FROM_A)

    companion object {
        private const val BASE_NOTE = "base note"
        private const val NOTE_FROM_A = "note from A"
        private const val NOTE_FROM_B = "note from B"
        private const val BASE_TAG = "family"
        private const val ADDED_TAG = "beach"
        private val MERGED_TAGS = listOf(ADDED_TAG, BASE_TAG)

        @get:ClassRule
        @JvmStatic
        val harness = E2eServer()
    }
}

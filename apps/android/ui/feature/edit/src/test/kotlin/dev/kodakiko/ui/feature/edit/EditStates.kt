package dev.kodakiko.ui.feature.edit

import androidx.compose.ui.graphics.Color
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.service.entries.CropRect
import dev.kodakiko.service.entries.FULL_CROP

object EditStates {
    val twoPhotos: List<EditPage> = listOf(
        EditPage(mediaId = P1, kind = MediaKind.PHOTO, width = 4000, height = 3000, caption = null),
        EditPage(mediaId = P2, kind = MediaKind.PHOTO, width = 3000, height = 4000, caption = null),
    )

    val mixed: List<EditPage> = listOf(
        EditPage(mediaId = P1, kind = MediaKind.PHOTO, width = 4000, height = 3000, caption = "Heron at dawn"),
        EditPage(mediaId = VID, kind = MediaKind.VIDEO, width = 1920, height = 1080, caption = null),
        EditPage(mediaId = P2, kind = MediaKind.PHOTO, width = 4000, height = 3000, caption = null),
    )

    val twoVoiceNotes: List<EditVoiceNote> = listOf(EditVoiceNote(VOX1, 20_000), EditVoiceNote(VOX2, 65_000))

    val todayAtNine = EditTime(takenAtUtc = "2026-11-21T08:00:00.000Z", utcOffsetMinutes = 60, nowUtc = NOW_UTC, deviceOffsetMinutes = DEVICE_OFFSET)

    fun body(
        pages: List<EditPage> = twoPhotos,
        voiceNotes: List<EditVoiceNote> = emptyList(),
        itemCount: Int = pages.size + voiceNotes.size,
        time: EditTime = todayAtNine,
        location: EntryLocation? = null,
        tags: List<String> = emptyList(),
        note: String = "",
        favorite: Boolean = false,
    ): EditBody = EditBody(
        pages = pages,
        voiceNotes = voiceNotes,
        itemCount = itemCount,
        time = time,
        location = location,
        tags = tags,
        note = note,
        favorite = favorite,
    )

    fun state(
        body: EditBody? = body(),
        isNew: Boolean = false,
        page: Int = 0,
        sheet: EditSheet? = null,
        noteEditor: NoteEditorUi? = null,
        timeEditor: TimeEditorUi? = null,
        recording: RecordingUi? = null,
        voicePlaying: String? = null,
        canPaste: Boolean = false,
        failed: Boolean = false,
        voiceFailed: Boolean = false,
    ): EditState = EditState(
        entryId = ENTRY,
        isNew = isNew,
        body = body,
        page = page,
        sheet = sheet,
        noteEditor = noteEditor,
        timeEditor = timeEditor,
        recording = recording,
        voicePlaying = voicePlaying,
        canPaste = canPaste,
        failed = failed,
        voiceFailed = voiceFailed,
    )

    val fullBody: EditBody = body(
        pages = mixed,
        voiceNotes = twoVoiceNotes,
        location = PARIS,
        tags = listOf("beach", "family"),
        note = "**swim** at the lake",
        favorite = true,
    )

    val full: EditState = state(body = fullBody)

    val newEntry: EditState = state(body = body(pages = twoPhotos.take(1)), isNew = true)

    fun editing(note: String, selection: TextSelection = TextSelection(note.length, note.length)): EditState =
        state(body = body(note = note), noteEditor = NoteEditorUi(selection))

    fun crop(rect: CropRect = FULL_CROP, busy: Boolean = false, failed: Boolean = false, loaded: Boolean = true): CropState = CropState(
        image = if (loaded) CropImage(solid(Color(0xFF7986CB), 400, 300), 4000, 3000) else null,
        rect = rect,
        busy = busy,
        failed = failed,
    )
}

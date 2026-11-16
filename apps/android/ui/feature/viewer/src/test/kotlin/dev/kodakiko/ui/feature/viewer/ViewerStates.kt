package dev.kodakiko.ui.feature.viewer

import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.MediaKind

object ViewerStates {
    val twoPhotos: List<ViewerPage> = listOf(
        ViewerPage(mediaId = "p1", kind = MediaKind.PHOTO, width = 4000, height = 3000, caption = null),
        ViewerPage(mediaId = "p2", kind = MediaKind.PHOTO, width = 3000, height = 4000, caption = null),
    )

    val mixed: List<ViewerPage> = listOf(
        ViewerPage(mediaId = "p1", kind = MediaKind.PHOTO, width = 4000, height = 3000, caption = "Heron at dawn"),
        ViewerPage(mediaId = "m2", kind = MediaKind.VIDEO, width = 1920, height = 1080, caption = null),
        ViewerPage(mediaId = "p3", kind = MediaKind.PHOTO, width = 4000, height = 3000, caption = null),
        ViewerPage(mediaId = "p4", kind = MediaKind.PHOTO, width = 4000, height = 3000, caption = null),
    )

    val todayAtNine = ViewerTime(takenAtUtc = "2026-11-21T08:00:00.000Z", utcOffsetMinutes = 60, nowUtc = NOW_UTC, deviceOffsetMinutes = DEVICE_OFFSET)

    fun body(
        pages: List<ViewerPage> = twoPhotos,
        time: ViewerTime = todayAtNine,
        location: EntryLocation? = null,
        voiceNotes: List<VoiceNote> = emptyList(),
        tags: List<String> = emptyList(),
        note: String = "",
        favorite: Boolean = false,
    ): ViewerBody = ViewerBody(
        pages = pages,
        time = time,
        location = location,
        voiceNotes = voiceNotes,
        tags = tags,
        note = note,
        favorite = favorite,
    )

    fun entry(
        body: ViewerBody? = body(),
        owner: Boolean = true,
        trashed: Boolean = false,
        unreadable: Boolean = false,
        sharedBy: String? = null,
    ): ViewerEntry = ViewerEntry(owner = owner, trashed = trashed, unreadable = unreadable, sharedBy = sharedBy, body = body)

    fun state(
        entry: ViewerEntry? = entry(),
        page: Int = 0,
        zoomed: Boolean = false,
        pinned: Boolean = false,
        sheet: ViewerSheet? = null,
        video: InlineVideoUi? = null,
        voicePlaying: String? = null,
        local: Boolean = false,
        sharingAvailable: Boolean = true,
    ): ViewerState = ViewerState(
        entryId = ENTRY,
        entry = entry,
        page = page,
        zoomed = zoomed,
        pinned = pinned,
        sheet = sheet,
        video = video,
        voicePlaying = voicePlaying,
        local = local,
        sharingAvailable = sharingAvailable,
    )

    val full: ViewerState = state(
        entry = entry(
            body = body(
                pages = mixed,
                location = BERLIN,
                voiceNotes = listOf(VoiceNote("v1", 20_000), VoiceNote("v2", 65_000)),
                tags = listOf("beach", "family"),
                note = "## Lake day\n\nWe **swam** at the [lake](https://example.com/lake).\n\n- towel\n- sunscreen",
            ),
        ),
    )

    fun reduce(state: ViewerState, action: ViewerAction): ViewerState = when (action) {
        is ViewerAction.PageChanged -> state.copy(page = action.page, zoomed = false)
        is ViewerAction.ZoomChanged -> state.copy(zoomed = action.zoomed)
        else -> state
    }
}

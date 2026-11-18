package dev.kodakiko.ui.feature.edit

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.markdown.MarkdownAction
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.service.entries.CropRect
import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.Route

@Immutable
data class EditPage(val mediaId: String, val kind: MediaKind, val width: Long, val height: Long, val caption: String?)

@Immutable
data class EditVoiceNote(val mediaId: String, val durationMs: Long?)

@Immutable
data class EditTime(val takenAtUtc: String, val utcOffsetMinutes: Long, val nowUtc: String, val deviceOffsetMinutes: Long)

@Immutable
data class EditBody(
    val pages: List<EditPage>,
    val voiceNotes: List<EditVoiceNote>,
    val itemCount: Int,
    val time: EditTime,
    val location: EntryLocation?,
    val tags: List<String>,
    val note: String,
    val favorite: Boolean,
)

sealed interface EditSheet {
    @Immutable
    data class MediaMenu(val mediaId: String, val kind: MediaKind, val canSetCover: Boolean) : EditSheet

    @Immutable
    data class Caption(val mediaId: String, val caption: String) : EditSheet

    @Immutable
    data class Remove(val mediaId: String, val kind: MediaKind, val last: Boolean) : EditSheet

    @Immutable
    data class ChooseTags(val tags: List<String>) : EditSheet
}

@Immutable
data class NoteEditorUi(val selection: TextSelection)

@Immutable
data class TimeEditorUi(val date: String, val time: String, val invalid: Boolean)

@Immutable
data class RecordingUi(val elapsedMs: Long)

@Immutable
data class EditState(
    val entryId: String,
    val isNew: Boolean,
    val body: EditBody?,
    val page: Int,
    val sheet: EditSheet?,
    val noteEditor: NoteEditorUi?,
    val timeEditor: TimeEditorUi?,
    val recording: RecordingUi?,
    val voicePlaying: String?,
    val canPaste: Boolean,
    val failed: Boolean,
    val voiceFailed: Boolean,
)

sealed interface EditAction {
    data object Back : EditAction

    data object Done : EditAction

    data object ViewEntry : EditAction

    data object Trash : EditAction

    data class PageChanged(val page: Int) : EditAction

    data class ScreenVisible(val visible: Boolean) : EditAction

    data class OpenMediaMenu(val mediaId: String) : EditAction

    data object DismissSheet : EditAction

    data class EditCaption(val mediaId: String) : EditAction

    data class SaveCaption(val mediaId: String, val caption: String) : EditAction

    data class SetCover(val mediaId: String) : EditAction

    data class ShareItem(val mediaId: String) : EditAction

    data class SaveToDevice(val mediaId: String) : EditAction

    data class Crop(val mediaId: String) : EditAction

    data class AskRemove(val mediaId: String) : EditAction

    data class Remove(val mediaId: String) : EditAction

    data class OpenPlayer(val mediaId: String) : EditAction

    data class Capture(val mode: CaptureMode) : EditAction

    data object AddFromDevice : EditAction

    data object ToggleFavorite : EditAction

    data object Paste : EditAction

    data object ToggleRecording : EditAction

    data object StopRecording : EditAction

    data object Share : EditAction

    data class ToggleVoice(val mediaId: String) : EditAction

    data class RemoveVoice(val mediaId: String) : EditAction

    data object ToggleTimeEditor : EditAction

    data class ApplyTime(val date: String, val time: String) : EditAction

    data object ResetTime : EditAction

    data object OpenMap : EditAction

    data object RemoveLocation : EditAction

    data object ChooseTags : EditAction

    data class ToggleTag(val tag: String) : EditAction

    data class RemoveTag(val tag: String) : EditAction

    data object EditTags : EditAction

    data object EditNote : EditAction

    data class NoteChanged(val text: String, val selection: TextSelection) : EditAction

    data class Toolbar(val action: MarkdownAction) : EditAction

    data object NoteDone : EditAction

    data class OpenLink(val url: String) : EditAction
}

sealed interface EditEvent {
    data object Back : EditEvent

    data object Home : EditEvent

    data class Open(val route: Route) : EditEvent

    data class Replace(val route: Route) : EditEvent

    data class OpenUri(val uri: String) : EditEvent

    data class Share(val entryIds: List<String>, val inKodakiko: Boolean) : EditEvent
}

@Immutable
data class CropImage(val bitmap: ImageBitmap, val width: Long, val height: Long)

@Immutable
data class CropState(val image: CropImage?, val rect: CropRect, val busy: Boolean, val failed: Boolean)

sealed interface CropAction {
    data class RectChanged(val rect: CropRect) : CropAction

    data object Apply : CropAction

    data object Cancel : CropAction
}

sealed interface CropEvent {
    data object Back : CropEvent

    data object Done : CropEvent
}

@Immutable
data class EntryNewState(val failed: Boolean)

sealed interface EntryNewEvent {
    data class Replace(val route: Route) : EntryNewEvent
}

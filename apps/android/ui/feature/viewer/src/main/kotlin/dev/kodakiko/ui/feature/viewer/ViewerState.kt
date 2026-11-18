package dev.kodakiko.ui.feature.viewer

import androidx.compose.runtime.Immutable
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.service.stats.DetailSection
import dev.kodakiko.ui.core.navigation.Route

@Immutable
data class ViewerPage(val mediaId: String, val kind: MediaKind, val width: Long, val height: Long, val caption: String?)

@Immutable
data class VoiceNote(val mediaId: String, val durationMs: Long?)

@Immutable
data class ViewerTime(val takenAtUtc: String, val utcOffsetMinutes: Long, val nowUtc: String, val deviceOffsetMinutes: Long)

@Immutable
data class ViewerBody(
    val pages: List<ViewerPage>,
    val time: ViewerTime,
    val location: EntryLocation?,
    val voiceNotes: List<VoiceNote>,
    val tags: List<String>,
    val note: String,
    val favorite: Boolean,
)

@Immutable
data class ViewerEntry(val owner: Boolean, val trashed: Boolean, val unreadable: Boolean, val sharedBy: String?, val body: ViewerBody?)

enum class ViewerSheet {
    MORE,
    CONFIRM_DELETE,
}

@Immutable
data class InlineVideoUi(val position: Int, val mediaId: String, val video: PreparedVideo, val muted: Boolean)

@Immutable
data class ViewerState(
    val entryId: String,
    val entry: ViewerEntry?,
    val page: Int,
    val zoomed: Boolean,
    val pinned: Boolean,
    val sheet: ViewerSheet?,
    val video: InlineVideoUi?,
    val voicePlaying: String?,
    val local: Boolean,
    val sharingAvailable: Boolean,
)

sealed interface ViewerAction {
    data class PageChanged(val page: Int) : ViewerAction

    data class ZoomChanged(val zoomed: Boolean) : ViewerAction

    data object ToggleFavorite : ViewerAction

    data object OpenDetails : ViewerAction

    data object Edit : ViewerAction

    data class OpenPlayer(val mediaId: String) : ViewerAction

    data object Back : ViewerAction

    data object OpenMap : ViewerAction

    data class OpenLink(val url: String) : ViewerAction

    data class ShowTag(val tag: String) : ViewerAction

    data object OpenMore : ViewerAction

    data object ToggleKeepOffline : ViewerAction

    data object AddMedia : ViewerAction

    data object Trash : ViewerAction

    data object AskDeletePermanently : ViewerAction

    data object ConfirmDeletePermanently : ViewerAction

    data object DismissSheet : ViewerAction

    data object Restore : ViewerAction

    data object Share : ViewerAction

    data class ToggleVoice(val mediaId: String) : ViewerAction

    data class ScreenVisible(val visible: Boolean) : ViewerAction

    data object ToggleMute : ViewerAction
}

sealed interface ViewerEvent {
    data class Open(val route: Route) : ViewerEvent

    data object Back : ViewerEvent

    data object Leave : ViewerEvent

    data class OpenUri(val uri: String) : ViewerEvent

    data class Share(val entryIds: List<String>, val inKodakiko: Boolean) : ViewerEvent
}

@Immutable
data class EntryDetailsState(val sections: List<DetailSection>?)

sealed interface EntryDetailsAction {
    data object Back : EntryDetailsAction
}

sealed interface EntryDetailsEvent {
    data object Back : EntryDetailsEvent
}

@Immutable
data class VideoPlayerState(val progress: Float = 0f, val video: PreparedVideo? = null, val problem: VideoProblem? = null)

sealed interface VideoPlayerAction {
    data object Retry : VideoPlayerAction

    data object Close : VideoPlayerAction

    data class PlaybackFailed(val problem: VideoProblem) : VideoPlayerAction
}

sealed interface VideoPlayerEvent {
    data object Back : VideoPlayerEvent
}

package dev.kodakiko.ui.feature.sharing

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import dev.kodakiko.service.sharing.AddEntriesResult
import dev.kodakiko.service.sharing.SharingErrorReason
import dev.kodakiko.service.sharing.SharingException
import dev.kodakiko.ui.core.R

@Immutable
sealed interface SharingMessage {
    data object Offline : SharingMessage

    data object UserNotFound : SharingMessage

    data class AlreadyShared(val username: String) : SharingMessage

    data object FlagOff : SharingMessage

    data object Failed : SharingMessage
}

fun sharingMessage(error: Throwable, recipient: String = ""): SharingMessage {
    if (error !is SharingException) return SharingMessage.Failed
    return when (error.reason) {
        SharingErrorReason.OFFLINE -> SharingMessage.Offline
        SharingErrorReason.USER_NOT_FOUND -> SharingMessage.UserNotFound
        SharingErrorReason.ALREADY_SHARED -> SharingMessage.AlreadyShared(recipient)
        SharingErrorReason.DISABLED -> SharingMessage.FlagOff
        SharingErrorReason.LOCKED, SharingErrorReason.NOT_OWNER -> SharingMessage.Failed
    }
}

fun sharingMessageText(resources: Resources, message: SharingMessage): String = when (message) {
    SharingMessage.Offline -> resources.getString(R.string.errors_sharing_offline)
    SharingMessage.UserNotFound -> resources.getString(R.string.errors_user_not_found)
    is SharingMessage.AlreadyShared -> resources.getString(R.string.errors_already_shared, message.username)
    SharingMessage.FlagOff -> resources.getString(R.string.shared_flag_off)
    SharingMessage.Failed -> resources.getString(R.string.errors_sharing_failed)
}

fun addEntriesResultText(resources: Resources, result: AddEntriesResult): String {
    val parts = buildList {
        add(resources.getString(R.string.shared_added, result.added.size))
        if (result.notSynced.isNotEmpty()) add(resources.getString(R.string.shared_not_synced, result.notSynced.size))
        if (result.alreadyIn.isNotEmpty()) add(resources.getString(R.string.shared_already_in, result.alreadyIn.size))
        if (result.failed.isNotEmpty()) add(resources.getString(R.string.shared_failed, result.failed.size))
    }
    return parts.joinToString(SharingConstants.PART_SEPARATOR)
}

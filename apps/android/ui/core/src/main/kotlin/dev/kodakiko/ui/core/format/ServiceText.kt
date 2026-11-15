package dev.kodakiko.ui.core.format

import android.content.res.Resources
import dev.kodakiko.service.sharing.CollectionName
import dev.kodakiko.service.stats.DetailLabel
import dev.kodakiko.service.stats.DetailSection
import dev.kodakiko.service.stats.DetailSectionKind
import dev.kodakiko.service.stats.DetailValue
import dev.kodakiko.service.sync.SyncBannerId
import dev.kodakiko.service.sync.SyncBannerModel
import dev.kodakiko.ui.core.R

private fun labelResource(label: DetailLabel): Int = when (label) {
    DetailLabel.ID -> R.string.details_id
    DetailLabel.TAKEN -> R.string.details_taken
    DetailLabel.CREATED -> R.string.details_created
    DetailLabel.EDITED -> R.string.details_edited
    DetailLabel.VERSION -> R.string.details_version
    DetailLabel.SYNC -> R.string.details_sync
    DetailLabel.LOCATION -> R.string.details_location
    DetailLabel.TAGS -> R.string.details_tags
    DetailLabel.NOTE_LENGTH -> R.string.details_note_length
    DetailLabel.FILE_NAME -> R.string.details_file_name
    DetailLabel.TYPE -> R.string.details_type
    DetailLabel.SIZE -> R.string.details_size
    DetailLabel.DIMENSIONS -> R.string.details_dimensions
    DetailLabel.DURATION -> R.string.details_duration
    DetailLabel.CAPTURED_AT -> R.string.details_captured_at
    DetailLabel.ORIGINAL -> R.string.details_original
    DetailLabel.THUMBNAIL -> R.string.details_thumbnail
    DetailLabel.MEDIA_ID -> R.string.details_media_id
    DetailLabel.ENCRYPTED_SIZE -> R.string.details_encrypted_size
}

fun detailLabelText(resources: Resources, label: DetailLabel): String = resources.getString(labelResource(label))

private fun storageText(resources: Resources, value: DetailValue.Storage): String {
    val local = resources.getString(if (value.onDevice) R.string.details_on_device else R.string.details_not_on_device)
    val upload = resources.getString(if (value.uploaded) R.string.details_uploaded else R.string.details_not_uploaded)
    return local + FormatConstants.STORAGE_STATE_SEPARATOR + upload
}

fun detailValueText(resources: Resources, value: DetailValue): String = when (value) {
    is DetailValue.Text -> value.text
    is DetailValue.Characters -> resources.getString(R.string.details_characters, value.count)
    is DetailValue.Bytes -> formatBytes(value.bytes)
    is DetailValue.Duration -> formatDuration(value.ms)
    is DetailValue.Storage -> storageText(resources, value)
    DetailValue.Unknown -> resources.getString(R.string.details_unknown)
}

fun detailSectionTitle(resources: Resources, section: DetailSection): String = when (section.kind) {
    DetailSectionKind.ENTRY -> resources.getString(R.string.details_entry)
    DetailSectionKind.PHOTO -> resources.getString(R.string.details_photo, requireNotNull(section.position))
    DetailSectionKind.VIDEO -> resources.getString(R.string.details_video, requireNotNull(section.position))
}

fun syncBannerText(resources: Resources, model: SyncBannerModel): String = when (model.id) {
    SyncBannerId.OFFLINE -> resources.getString(R.string.sync_offline)
    SyncBannerId.DEAD -> resources.getQuantityString(R.plurals.sync_dead, model.count, model.count)
    SyncBannerId.WAITING_FOR_WIFI -> resources.getQuantityString(R.plurals.sync_waiting_for_wifi, model.count, model.count)
}

fun collectionNameText(resources: Resources, name: CollectionName): String = when (name) {
    CollectionName.Unnamed -> resources.getString(R.string.shared_unnamed)
    is CollectionName.Named -> name.name
}

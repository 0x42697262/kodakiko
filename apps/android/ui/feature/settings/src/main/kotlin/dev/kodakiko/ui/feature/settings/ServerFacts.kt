package dev.kodakiko.ui.feature.settings

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import dev.kodakiko.contract.API_VERSION
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.format.formatBytes

@Immutable
data class ServerUsage(val usedBytes: Long, val quotaBytes: Long?)

@Immutable
data class ServerFactsInput(
    val serverUrl: String?,
    val config: PublicConfigDto?,
    val user: ServerUsage?,
    val account: AccountFacts,
    val fingerprint: String,
    val keyStorage: KeyStorage,
    val encryptThumbnails: Boolean,
    val local: Boolean,
)

@Immutable
data class ServerFactRow(val label: String, val value: String)

@Immutable
data class ServerFactSection(val title: String, val rows: List<ServerFactRow>)

private fun Resources.onOff(value: Boolean): String = getString(if (value) R.string.server_on else R.string.server_off)

private fun Resources.row(label: Int, value: String): ServerFactRow = ServerFactRow(getString(label), value)

private fun serverSection(resources: Resources, input: ServerFactsInput, config: PublicConfigDto): ServerFactSection = with(resources) {
    val rows = mutableListOf(
        row(R.string.server_address, input.serverUrl.orEmpty()),
        row(R.string.server_version, getString(R.string.server_version_value, config.serverVersion, API_VERSION)),
        row(R.string.server_signup, getString(if (config.signupMode == SignupMode.INVITE) R.string.server_signup_invite else R.string.server_signup_open)),
        row(R.string.server_sharing, onOff(config.sharingEnabled)),
        row(R.string.server_links, onOff(config.publicLinksEnabled)),
        row(R.string.server_max_media, config.maxMediaPerEntry.toString()),
        row(R.string.server_max_upload, formatBytes(config.maxUploadBytes)),
        row(R.string.server_trash_days, getString(R.string.server_days, config.trashDays)),
    )
    input.user?.let { user ->
        rows += row(R.string.server_used, getString(R.string.server_used_value, formatBytes(user.usedBytes), formatBytes(user.quotaBytes ?: config.defaultQuotaBytes)))
    }
    ServerFactSection(getString(R.string.server_section), rows)
}

private fun accountSection(resources: Resources, input: ServerFactsInput): ServerFactSection = with(resources) {
    val account = input.account
    val rows = mutableListOf(row(R.string.server_account_id, account.userId), row(R.string.server_username, account.username))
    if (!input.local && input.fingerprint.isNotEmpty()) rows += row(R.string.server_code, input.fingerprint)
    rows += row(R.string.server_hashing, getString(R.string.server_hashing_value, account.opsLimit, formatBytes(account.memLimitBytes)))
    rows += row(R.string.server_recovery, getString(if (account.recoveryConfirmed) R.string.server_recovery_confirmed else R.string.server_recovery_not_confirmed))
    ServerFactSection(getString(R.string.server_account), rows)
}

private fun keysSection(resources: Resources, input: ServerFactsInput): ServerFactSection = with(resources) {
    val storage = input.keyStorage
    val keyStorage = when {
        !storage.persistent -> R.string.server_key_not_kept
        storage.biometric -> R.string.server_key_biometric
        else -> R.string.server_key_kept
    }
    ServerFactSection(
        getString(R.string.server_keys),
        listOf(
            row(R.string.server_key_storage, getString(keyStorage)),
            row(R.string.server_media, getString(R.string.server_media_value, formatBytes(1L shl STREAM_CHUNK_SIZE_EXPONENT))),
            row(R.string.server_metadata, getString(R.string.server_metadata_value)),
            row(R.string.server_sharing_keys, getString(R.string.server_sharing_keys_value)),
            row(R.string.server_thumbnails, getString(if (input.encryptThumbnails) R.string.server_thumbnails_encrypted else R.string.server_thumbnails_plain)),
        ),
    )
}

fun serverFactSections(resources: Resources, input: ServerFactsInput): List<ServerFactSection> = buildList {
    val config = input.config
    if (!input.local && config != null) add(serverSection(resources, input, config))
    add(accountSection(resources, input))
    add(keysSection(resources, input))
}

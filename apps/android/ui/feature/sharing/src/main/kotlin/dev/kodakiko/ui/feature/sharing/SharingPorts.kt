package dev.kodakiko.ui.feature.sharing

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.shareout.ShareOutService
import dev.kodakiko.service.sharing.CollectionNames
import dev.kodakiko.service.sharing.SharingService
import kotlinx.coroutines.flow.StateFlow

data class SharedJournalRow(val id: String, val role: CollectionRole, val ownerUsername: String)

fun interface SharedJournalRows {
    suspend fun load(): List<SharedJournalRow>
}

fun interface SharingThumbnails {
    suspend fun load(entryId: String, mediaId: String): ImageBitmap?
}

object NoSharingThumbnails : SharingThumbnails {
    override suspend fun load(entryId: String, mediaId: String): ImageBitmap? = null
}

val LocalSharingThumbnails: ProvidableCompositionLocal<SharingThumbnails> = staticCompositionLocalOf { NoSharingThumbnails }

class SharingDeps(
    val sharing: SharingService,
    val names: CollectionNames,
    val rows: SharedJournalRows,
    val index: IndexService,
    val shareOut: ShareOutService,
    val config: ConfigService,
    val accountMode: StateFlow<AccountMode>,
    val syncedAt: StateFlow<Long?>,
    val syncNow: suspend () -> Unit,
)

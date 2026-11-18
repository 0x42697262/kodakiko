package dev.kodakiko.ui.feature.gallery

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.ui.core.notice.GalleryNotice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

interface GallerySettings {
    val settings: StateFlow<DeviceSettings>

    fun update(change: (DeviceSettings) -> DeviceSettings)
}

interface GalleryAccount {
    val username: StateFlow<String>

    val mode: StateFlow<AccountMode>

    suspend fun lockNow()

    suspend fun signOut()
}

fun interface GallerySync {
    suspend fun syncNow()
}

interface GalleryClock {
    fun nowMs(): Long

    fun todayLocalDate(): String
}

fun interface GalleryThumbnails {
    suspend fun load(entryId: String, mediaId: String): ImageBitmap?
}

object NoGalleryThumbnails : GalleryThumbnails {
    override suspend fun load(entryId: String, mediaId: String): ImageBitmap? = null
}

val LocalGalleryThumbnails: ProvidableCompositionLocal<GalleryThumbnails> = staticCompositionLocalOf { NoGalleryThumbnails }

class GalleryDeps(
    val index: IndexService,
    val entries: EntriesService,
    val settings: GallerySettings,
    val filter: GalleryFilterStore,
    val notices: Observable<GalleryNotice?>,
    val account: GalleryAccount,
    val sync: GallerySync,
    val config: ConfigService,
    val haptics: (HapticKind) -> Unit,
    val clock: GalleryClock,
    val random: () -> Double,
    val dispatcher: CoroutineDispatcher,
)

package dev.kodakiko.app.gallery

import dev.kodakiko.service.sync.SyncService
import dev.kodakiko.ui.feature.gallery.GalleryClock
import dev.kodakiko.ui.feature.gallery.GallerySync
import java.time.LocalDate
import java.time.ZoneId

object SystemGalleryClock : GalleryClock {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun todayLocalDate(): String = LocalDate.now(ZoneId.systemDefault()).toString()
}

class AppGallerySync(private val sync: SyncService) : GallerySync {
    override suspend fun syncNow() {
        sync.syncNow()
    }
}

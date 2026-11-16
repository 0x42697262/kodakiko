package dev.kodakiko.app.gallery

import dev.kodakiko.service.entries.EntriesSyncPort
import dev.kodakiko.ui.feature.gallery.GalleryClock
import dev.kodakiko.ui.feature.gallery.GallerySync
import java.time.LocalDate
import java.time.ZoneId

object SystemGalleryClock : GalleryClock {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun todayLocalDate(): String = LocalDate.now(ZoneId.systemDefault()).toString()
}

object UnwiredGallerySync : GallerySync {
    override suspend fun syncNow() = Unit
}

object UnwiredEntriesSync : EntriesSyncPort {
    override fun notifyLocalWrite() = Unit

    override fun isEntryInFlight(entryId: String): Boolean = false
}

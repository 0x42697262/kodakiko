package dev.kodakiko.ui.feature.links

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.service.links.PublicLinksService

interface LinkSharing {
    fun copy(text: String)

    fun share(text: String)
}

fun interface LinkThumbnails {
    suspend fun load(entryId: String, mediaId: String): ImageBitmap?
}

object NoLinkThumbnails : LinkThumbnails {
    override suspend fun load(entryId: String, mediaId: String): ImageBitmap? = null
}

val LocalLinkThumbnails: ProvidableCompositionLocal<LinkThumbnails> = staticCompositionLocalOf { NoLinkThumbnails }

class LinksDeps(
    val links: PublicLinksService,
    val readMetadata: suspend (String) -> EntryMetadataV1,
    val sharing: LinkSharing,
    val defaultTitle: String,
    val nowMs: () -> Long,
)

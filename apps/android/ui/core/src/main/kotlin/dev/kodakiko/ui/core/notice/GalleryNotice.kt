package dev.kodakiko.ui.core.notice

sealed interface GalleryNotice {
    data class Saved(val atMs: Long) : GalleryNotice

    data object Gone : GalleryNotice

    data class Trashed(val entryId: String) : GalleryNotice
}

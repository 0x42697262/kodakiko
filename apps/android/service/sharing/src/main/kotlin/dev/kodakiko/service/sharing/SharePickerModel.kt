package dev.kodakiko.service.sharing

import dev.kodakiko.js.jsTrim

enum class ShareTextOption {
    NONE,
    CAPTIONS,
    CAPTIONS_AND_NOTE,
}

data class ShareItem(val mediaId: String, val bytes: Long, val caption: String? = null)

data class ShareSelection(val count: Int, val bytes: Long, val warn: Boolean)

fun shareSelection(items: List<ShareItem>, chosen: Set<String>): ShareSelection {
    val picked = items.filter { it.mediaId in chosen }
    val bytes = picked.sumOf { it.bytes }
    return ShareSelection(count = picked.size, bytes = bytes, warn = bytes > SharingConstants.SHARE_WARN_BYTES)
}

fun shareText(option: ShareTextOption, items: List<ShareItem>, chosen: Set<String>, note: String): String? {
    if (option == ShareTextOption.NONE) return null
    val captions = items.filter { it.mediaId in chosen }.mapNotNull { it.caption }
    val noteText = if (option == ShareTextOption.CAPTIONS_AND_NOTE) jsTrim(note) else ""
    val parts = listOf(captions.joinToString(SharingConstants.CAPTION_SEPARATOR), noteText).filter { it.isNotEmpty() }
    return if (parts.isEmpty()) null else parts.joinToString(SharingConstants.TEXT_PART_SEPARATOR)
}

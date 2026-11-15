package dev.kodakiko.data.media

data class ThumbnailImageRequest(val entryId: String, val mediaId: String)

data class OriginalImageRequest(val entryId: String, val mediaId: String, val mimeType: String?)

class MediaUnavailableException : Exception(MediaText.MEDIA_UNAVAILABLE)

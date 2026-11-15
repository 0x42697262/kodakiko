package dev.kodakiko.data.media

import java.io.IOException

class DownloadCancelledError : Exception(MediaText.DOWNLOAD_CANCELLED)

class ObjectUnavailableError : Exception(MediaText.OBJECT_UNAVAILABLE)

class DownloadSizeMismatchError(val expected: Long, val actual: Long) : Exception(MediaText.sizeMismatch(expected, actual))

class DownloadHttpError(val status: Int) : IOException(MediaText.downloadHttpStatus(status))

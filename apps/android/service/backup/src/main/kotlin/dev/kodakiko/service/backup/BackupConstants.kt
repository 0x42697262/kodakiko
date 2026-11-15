package dev.kodakiko.service.backup

object BackupConstants {
    const val DATE_CHARS = 10
    const val ZIP_MIME_TYPE = "application/zip"
    const val LINE_BREAK = "\n"
    const val REASON_MISSING = "missing"
    const val REASON_TOO_LARGE = "too large"
    const val NOTE_SUFFIX = "/"
    const val NO_PASSPHRASE = ""
    const val FIRST_PART = 1

    val BYTE_ORDER_MARK: ByteArray get() = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}

object BackupText {
    const val EXPORT_CANCELLED = "The export was cancelled."
    const val PART_NOT_CREATED = "The chosen folder refused the export file: "
}

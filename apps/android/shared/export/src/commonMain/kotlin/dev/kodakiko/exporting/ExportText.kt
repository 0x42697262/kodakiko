package dev.kodakiko.exporting

object ExportText {
    const val MISSING_HEADER = "These items could not be exported:"
    const val INVALID_ENTRY_NOTE = "This file is not an entry from a Kodakiko export."
    const val KDKX_MALFORMED = "Not a Kodakiko encrypted export, or the file is damaged."
    const val ZIP_MALFORMED = "Not a ZIP archive, or the archive is damaged."
    const val ZIP_UNSUPPORTED = "This ZIP archive uses a feature Kodakiko cannot read (ZIP64 or an unknown compression method)."
    const val ZIP_NAME_TOO_LONG = "A file name in the archive is longer than 65535 bytes."
    const val ZIP_DATE_OUT_OF_RANGE = "A file date in the archive is outside 1980-2099."
    const val ZIP_TOO_LARGE = "The archive would need ZIP64 (more than 4 GiB or 65535 files)."
    const val ZIP_FILE_STILL_OPEN = "The previous file in the archive is not finished yet."
    const val ZIP_FILE_FINISHED = "This file in the archive is already finished."
    const val ZIP_ARCHIVE_FINISHED = "The archive is already finished."

    fun readme(date: String): String = listOf(
        "Kodakiko journal export, $date.",
        "",
        "Each entry is a folder named YEAR/MONTH/DATE_TIME_ID/. It holds entry.md (the note, with the",
        "date, tags, location and media list at the top) and the photos and videos numbered 001, 002, ...",
        "",
        "These files are NOT encrypted. Store them somewhere safe.",
        "",
    ).joinToString("\n")

    fun missingLine(folder: String, file: String, reason: String): String = "$folder$file\t$reason"
}

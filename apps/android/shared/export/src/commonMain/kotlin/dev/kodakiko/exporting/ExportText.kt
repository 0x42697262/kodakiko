package dev.kodakiko.exporting

object ExportText {
    const val MISSING_HEADER = "These items could not be exported:"
    const val INVALID_ENTRY_NOTE = "This file is not an entry from a Kodakiko export."
    const val KDKX_MALFORMED = "Not a Kodakiko encrypted export, or the file is damaged."

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

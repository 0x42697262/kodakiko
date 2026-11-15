package dev.kodakiko.index

const val NOTE_PREVIEW_CHARS = 140
const val TOKEN_MIN_CHARS = 2
const val MAX_INDEX_TOKENS_PER_ENTRY = 500
const val YEAR_END = 4
const val MONTH_DAY_START = 5
const val LOCAL_DATE_SEPARATOR = "-"
const val SORT_BEFORE = -1
const val SORT_AFTER = 1

internal val TOKEN_SEPARATOR_PATTERN = Regex("[^\\p{L}\\p{N}]+")

internal object IndexConstants {
    const val MULTIPLE_MEDIA_MIN = 1
    const val MIN_YEARS_AGO = 1.0
    const val RANDOM_MULTIPLIER = 1_103_515_245.0
    const val RANDOM_INCREMENT = 12_345.0
    const val RANDOM_MODULUS = 2_147_483_648.0
}

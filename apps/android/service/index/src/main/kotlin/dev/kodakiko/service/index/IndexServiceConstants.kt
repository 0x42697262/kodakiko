package dev.kodakiko.service.index

object IndexServiceConstants {
    const val INDEX_BUILD_YIELD_EVERY = 200
    const val INDEX_UPDATE_DEBOUNCE_MS = 100L
    const val TAG_SUGGESTIONS_MAX = 8
    const val UNKNOWN_HOME_COLLECTION_ID = ""
    const val SEARCH_TEXT_SEPARATOR = "\n"
    const val UNLOCK_BUDGET_MS = 2_000L
    const val INDEX_BENCHMARK_ENTRIES = 20_000
}

object IndexBenchmarkConstants {
    const val WORDS_PER_NOTE = 30
    const val TAGS = 40
    const val TAGS_PER_ENTRY = 2
    const val QUERY_PREFIX = "abc"
    const val WORD_LETTERS = "abcdefghijklmnopqrstuvwxyz"
    const val WORD_LENGTH = 5
    const val SPACING_MINUTES = 37L
    const val MS_PER_MINUTE = 60_000L
    const val MS_PER_SECOND = 1_000.0
    const val BYTE_MASK = 0xFF
    const val OWNER = "benchmark-owner"
    const val COLLECTION = "benchmark-collection"
    const val MIME = "image/jpeg"
    const val SIDE = 512L
    const val BYTES = 1_024L
    const val TAG_PREFIX = "tag"
    const val WORD_SEPARATOR = " "
    const val REVISION = 1L
    const val MEMBERSHIP_SEQ = 0L
    const val BINDING_SEPARATOR = "/"
}

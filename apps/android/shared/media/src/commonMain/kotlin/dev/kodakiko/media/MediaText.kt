package dev.kodakiko.media

object MediaText {
    const val OUT_OF_RANGE = "The file ends before the expected data."
    const val MALFORMED_STRUCTURE = "The file structure is not valid."
    const val NO_TIMES = "At least one capture time is required."
    const val DATA_VIEW_OUT_OF_BOUNDS = "Offset is outside the bounds of the DataView"
    const val NOT_AN_IMAGE_CONTAINER = "There is no EXIF locator for this container."
    const val TOO_MANY_BOXES = "too many boxes"
    const val TRUNCATED_BOX = "truncated box"
    const val BAD_BOX_SIZE = "bad box size"
    const val FRAGMENTED_MP4 = "fragmented MP4"
    const val MISSING_MOOV = "missing moov"
    const val MOOV_TOO_LARGE = "moov too large"
    const val TRUNCATED_CHILD = "truncated child"
    const val BAD_CHILD_SIZE = "bad child size"
    const val BAD_CHUNK_TABLE = "bad chunk table"
    const val CHUNK_OFFSET_OVERFLOW = "chunk offset overflow"
    const val CHUNK_OFFSET_OUTSIDE_MDAT = "chunk offset outside mdat"
}

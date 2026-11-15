package dev.kodakiko.index

import dev.kodakiko.index.IndexConstants.RANDOM_INCREMENT
import dev.kodakiko.index.IndexConstants.RANDOM_MODULUS
import dev.kodakiko.index.IndexConstants.RANDOM_MULTIPLIER
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.truncate

private fun seededRandom(seed: Double): () -> Double {
    var state = abs(truncate(seed)) % RANDOM_MODULUS
    return {
        state = (state * RANDOM_MULTIPLIER + RANDOM_INCREMENT) % RANDOM_MODULUS
        state / RANDOM_MODULUS
    }
}

private fun shuffled(ids: List<String>, seed: Double): List<String> {
    val shuffled = ids.toMutableList()
    val next = seededRandom(seed)
    for (position in shuffled.size - 1 downTo 1) {
        val other = floor(next() * (position + 1)).toInt()
        val swapped = shuffled[other]
        shuffled[other] = shuffled[position]
        shuffled[position] = swapped
    }
    return shuffled
}

private fun measureOrder(left: IndexedEntry, right: IndexedEntry, key: GallerySortKey): Int = when (key) {
    GallerySortKey.NOTE_LENGTH -> left.notePreview.length.compareTo(right.notePreview.length)
    GallerySortKey.MEDIA_COUNT -> left.mediaCount.compareTo(right.mediaCount)
    else -> left.takenAtUtc.compareTo(right.takenAtUtc)
}

private fun compareEntries(leftId: String, rightId: String, left: IndexedEntry?, right: IndexedEntry?, sort: GallerySort): Int {
    if (left == null || right == null) return if (left == null) SORT_AFTER else SORT_BEFORE
    val byMeasure = measureOrder(left, right, sort.key)
    if (byMeasure != 0) return (if (byMeasure < 0) SORT_BEFORE else SORT_AFTER) * (if (sort.descending) SORT_BEFORE else SORT_AFTER)
    if (left.takenAtUtc != right.takenAtUtc) return if (left.takenAtUtc < right.takenAtUtc) SORT_AFTER else SORT_BEFORE
    return if (leftId < rightId) SORT_BEFORE else SORT_AFTER
}

fun sortEntryIds(ids: List<String>, get: (String) -> IndexedEntry?, sort: GallerySort, seed: Double): List<String> {
    if (sort.key == GallerySortKey.RANDOM) return shuffled(ids, seed)
    return ids.sortedWith { leftId, rightId -> compareEntries(leftId, rightId, get(leftId), get(rightId), sort) }
}

package dev.kodakiko.js

import dev.kodakiko.js.JsSortConstants.MIN_MERGE
import dev.kodakiko.js.JsSortConstants.PAIR

private class Run(val base: Int, val length: Int)

private class StableSort<T>(private val work: MutableList<T>, private val compare: (T, T) -> Int) {
    private val runs = ArrayList<Run>()

    fun sort() {
        val size = work.size
        if (size < PAIR) return
        val minRun = minRunLength(size)
        var low = 0
        var remaining = size
        while (remaining != 0) {
            var currentRun = countAndMakeRun(low, low + remaining)
            if (currentRun < minRun) {
                val forced = minOf(minRun, remaining)
                binaryInsertionSort(low, low + currentRun, low + forced)
                currentRun = forced
            }
            runs.add(Run(low, currentRun))
            mergeCollapse()
            low += currentRun
            remaining -= currentRun
        }
        mergeForceCollapse()
    }

    private fun minRunLength(size: Int): Int {
        var length = size
        var remainder = 0
        while (length >= MIN_MERGE) {
            remainder = remainder or (length and 1)
            length = length shr 1
        }
        return length + remainder
    }

    private fun countAndMakeRun(lowArg: Int, high: Int): Int {
        val low = lowArg + 1
        if (low == high) return 1
        var runLength = PAIR
        val descending = compare(work[low], work[lowArg]) < 0
        var previous = work[low]
        for (index in low + 1 until high) {
            val current = work[index]
            val order = compare(current, previous)
            if (if (descending) order >= 0 else order < 0) break
            previous = current
            runLength += 1
        }
        if (descending) work.subList(lowArg, lowArg + runLength).reverse()
        return runLength
    }

    private fun binaryInsertionSort(low: Int, sortedEnd: Int, high: Int) {
        val first = if (low == sortedEnd) sortedEnd + 1 else sortedEnd
        for (start in first until high) {
            val pivot = work[start]
            var left = low
            var right = start
            while (left < right) {
                val middle = left + ((right - left) shr 1)
                if (compare(pivot, work[middle]) < 0) right = middle else left = middle + 1
            }
            for (position in start downTo left + 1) work[position] = work[position - 1]
            work[left] = pivot
        }
    }

    private fun length(index: Int): Int = runs[index].length

    private fun invariantHolds(index: Int): Boolean = index < PAIR || length(index - PAIR) > length(index - 1) + length(index)

    private fun mergeCollapse() {
        while (runs.size > 1) {
            var index = runs.size - PAIR
            if (!invariantHolds(index + 1) || !invariantHolds(index)) {
                if (length(index - 1) < length(index + 1)) index -= 1
                mergeAt(index)
            } else if (length(index) <= length(index + 1)) {
                mergeAt(index)
            } else {
                break
            }
        }
    }

    private fun mergeForceCollapse() {
        while (runs.size > 1) {
            var index = runs.size - PAIR
            if (index > 0 && length(index - 1) < length(index + 1)) index -= 1
            mergeAt(index)
        }
    }

    private fun mergeAt(index: Int) {
        val first = runs[index]
        val second = runs[index + 1]
        runs[index] = Run(first.base, first.length + second.length)
        runs.removeAt(index + 1)
        val left = ArrayList(work.subList(first.base, first.base + first.length))
        var leftCursor = 0
        var rightCursor = second.base
        val rightEnd = second.base + second.length
        var target = first.base
        while (leftCursor < left.size && rightCursor < rightEnd) {
            work[target++] = if (compare(work[rightCursor], left[leftCursor]) < 0) work[rightCursor++] else left[leftCursor++]
        }
        while (leftCursor < left.size) work[target++] = left[leftCursor++]
    }
}

fun <T> jsStableSort(items: Collection<T>, compare: (T, T) -> Int): List<T> {
    val work = ArrayList(items)
    StableSort(work, compare).sort()
    return work
}

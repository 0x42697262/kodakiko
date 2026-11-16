package dev.kodakiko.index

import dev.kodakiko.js.jsLocaleCompare
import dev.kodakiko.js.jsStableSort

fun orderTags(counted: List<TagCount>, order: List<String>): List<TagCount> {
    val counts = counted.associate { it.tag to it.count }
    val ordered = order.map { tag -> TagCount(tag, counts[tag] ?: 0) }
    val listed = order.toSet()
    val rest = jsStableSort(counted.filter { it.tag !in listed }) { left, right -> jsLocaleCompare(left.tag, right.tag) }
    return ordered + rest
}

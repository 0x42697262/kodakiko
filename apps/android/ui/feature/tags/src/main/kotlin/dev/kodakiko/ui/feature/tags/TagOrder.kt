package dev.kodakiko.ui.feature.tags

fun moveTag(order: List<String>, tag: String, delta: Int): List<String> {
    val from = order.indexOf(tag)
    val to = from + delta
    if (from < 0 || to < 0 || to >= order.size) return order.toList()
    return order.toMutableList().apply {
        removeAt(from)
        add(to, tag)
    }
}

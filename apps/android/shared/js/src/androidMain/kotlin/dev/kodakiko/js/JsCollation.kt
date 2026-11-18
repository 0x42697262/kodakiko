package dev.kodakiko.js

import java.text.Collator
import java.util.Locale

actual fun jsLocaleCompare(left: String, right: String): Int {
    val collator = Collator.getInstance(Locale.getDefault())
    collator.strength = Collator.TERTIARY
    collator.decomposition = Collator.CANONICAL_DECOMPOSITION
    return collator.compare(left, right)
}

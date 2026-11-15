package dev.kodakiko.account

import dev.kodakiko.crypto.passwordToBytes
import dev.kodakiko.crypto.wipe

internal fun codePointsOf(text: String): List<String> {
    val codePoints = ArrayList<String>(text.length)
    var index = 0
    while (index < text.length) {
        val isPair = text[index].isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true
        val length = if (isPair) SURROGATE_PAIR_LENGTH else 1
        codePoints.add(text.substring(index, index + length))
        index += length
    }
    return codePoints
}

private fun isLoneSurrogate(codePoint: String): Boolean = codePoint.length == 1 && codePoint[0].isSurrogate()

private fun normalizeWellFormed(segment: String): String {
    if (segment.isEmpty()) return segment
    val bytes = passwordToBytes(segment)
    try {
        return bytes.decodeToString()
    } finally {
        wipe(bytes)
    }
}

internal fun normalizeNfkc(text: String): String {
    if (text.none { it.isSurrogate() }) return normalizeWellFormed(text)
    val normalized = StringBuilder(text.length)
    val segment = StringBuilder()
    for (codePoint in codePointsOf(text)) {
        if (isLoneSurrogate(codePoint)) {
            normalized.append(normalizeWellFormed(segment.toString())).append(codePoint)
            segment.clear()
        } else {
            segment.append(codePoint)
        }
    }
    return normalized.append(normalizeWellFormed(segment.toString())).toString()
}

package dev.kodakiko.markdown

internal class TextSlice private constructor(private val base: String, private val offset: Int, override val length: Int) : CharSequence {
    override fun get(index: Int): Char = base[offset + index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = TextSlice(base, offset + startIndex, endIndex - startIndex)

    override fun toString(): String = base.substring(offset, offset + length)

    companion object {
        fun of(text: CharSequence): CharSequence = if (text is TextSlice) text else TextSlice(text.toString(), 0, text.length)
    }
}

internal fun CharSequence.dropStart(count: Int): CharSequence = subSequence(minOf(count, length), length)

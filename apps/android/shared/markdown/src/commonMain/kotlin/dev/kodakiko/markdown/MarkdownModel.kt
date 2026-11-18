package dev.kodakiko.markdown

sealed interface Inline {
    data class Text(val text: String) : Inline

    data class Strong(val children: List<Inline>) : Inline

    data class Em(val children: List<Inline>) : Inline

    data class Del(val children: List<Inline>) : Inline

    data class Code(val text: String) : Inline

    data class Link(val href: String?, val children: List<Inline>) : Inline

    data object Break : Inline
}

sealed interface Block {
    data class Heading(val level: Int, val children: List<Inline>) : Block

    data class Paragraph(val children: List<Inline>) : Block

    data class Code(val text: String) : Block

    data class Quote(val blocks: List<Block>) : Block

    data class ListBlock(val ordered: Boolean, val start: Long, val items: List<ListItem>) : Block

    data object Rule : Block
}

data class ListItem(val checked: Boolean?, val blocks: List<Block>)

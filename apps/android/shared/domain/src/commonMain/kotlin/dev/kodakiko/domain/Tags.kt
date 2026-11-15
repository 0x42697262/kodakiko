package dev.kodakiko.domain

import dev.kodakiko.contract.TAGS_PER_ENTRY_MAX
import dev.kodakiko.contract.TAG_MAX_CHARS
import dev.kodakiko.contract.WireEnum
import dev.kodakiko.media.collapseJsWhitespace
import dev.kodakiko.media.jsTrim

enum class TagRejection(override val wire: String) : WireEnum {
    EMPTY("empty"),
    TOO_LONG("tooLong"),
    TOO_MANY("tooMany"),
    DUPLICATE("duplicate"),
}

data class AddTagResult(val tags: List<String>, val rejected: TagRejection?)

private fun cleanTag(input: String): String = collapseJsWhitespace(jsTrim(normalizeNfkc(input)), TAG_WORD_SEPARATOR).lowercase()

fun normalizeTag(input: String): String? {
    val normalized = cleanTag(input)
    return if (normalized.isEmpty() || normalized.length > TAG_MAX_CHARS) null else normalized
}

fun sortTags(tags: List<String>): List<String> = tags.sorted()

fun addTag(tags: List<String>, raw: String): AddTagResult {
    val normalized = cleanTag(raw)
    if (normalized.isEmpty()) return AddTagResult(tags, TagRejection.EMPTY)
    if (normalized.length > TAG_MAX_CHARS) return AddTagResult(tags, TagRejection.TOO_LONG)
    if (normalized in tags) return AddTagResult(tags, TagRejection.DUPLICATE)
    if (tags.size >= TAGS_PER_ENTRY_MAX) return AddTagResult(tags, TagRejection.TOO_MANY)
    return AddTagResult(sortTags(tags + normalized), null)
}

fun removeTag(tags: List<String>, tag: String): List<String> {
    val normalized = normalizeTag(tag) ?: return tags
    return tags.filter { it != normalized }
}

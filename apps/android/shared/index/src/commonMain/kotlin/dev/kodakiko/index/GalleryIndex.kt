package dev.kodakiko.index

import dev.kodakiko.domain.normalizeTag
import dev.kodakiko.index.IndexConstants.MIN_YEARS_AGO
import dev.kodakiko.index.IndexConstants.MULTIPLE_MEDIA_MIN
import dev.kodakiko.js.JsDate
import dev.kodakiko.js.jsStableSort
import dev.kodakiko.js.jsStringToNumber
import dev.kodakiko.js.jsTrim
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

private class Stored(
    val entry: IndexedEntry,
    val takenAtMs: Double,
    val tokens: List<String>,
    val indexedTags: List<String>,
    val counted: Boolean,
)

private fun addTo(map: MutableMap<String, MutableSet<String>>, key: String, id: String) {
    map.getOrPut(key) { LinkedHashSet() }.add(id)
}

private fun removeFrom(map: MutableMap<String, MutableSet<String>>, key: String, id: String) {
    val set = map[key] ?: return
    set.remove(id)
    if (set.isEmpty()) map.remove(key)
}

private fun intersect(sets: List<Set<String>>): Set<String> {
    val bySize = jsStableSort(sets) { left, right -> left.size - right.size }
    val smallest = bySize.firstOrNull() ?: return emptySet()
    val others = bySize.drop(1)
    val result = LinkedHashSet<String>()
    for (id in smallest) {
        if (others.all { id in it }) result.add(id)
    }
    return result
}

private fun lowerBound(sorted: List<String>, target: String): Int {
    var low = 0
    var high = sorted.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (sorted[middle] < target) low = middle + 1 else high = middle
    }
    return low
}

private fun normalizedPrefix(prefix: String): String? = if (jsTrim(prefix).isEmpty()) "" else normalizeTag(prefix)

private fun yearOf(localDate: String): Double = jsStringToNumber(localDate.take(YEAR_END))

private fun sliceEnd(size: Int, end: Int): Int = if (end < 0) max(size + end, 0) else min(end, size)

private fun sortResultOf(difference: Double): Int = if (difference.isNaN()) 0 else difference.sign.toInt()

private fun attributeOf(entry: IndexedEntry, key: AttributeKey, homeCollectionId: String): Boolean = when (key) {
    AttributeKey.VIDEO -> entry.hasVideo
    AttributeKey.NOTE -> entry.notePreview != ""
    AttributeKey.MULTIPLE -> entry.mediaCount > MULTIPLE_MEDIA_MIN
    AttributeKey.SHARED -> !entry.isOwn || entry.collectionIds.any { it != homeCollectionId }
    AttributeKey.PENDING -> entry.pending
    AttributeKey.FAVORITE -> entry.favorite == true
}

fun attributesMatch(entry: IndexedEntry, filters: AttributeFilters, homeCollectionId: String): Boolean {
    for (key in AttributeKey.entries) {
        val state = filters[key] ?: TriState.OFF
        if (state == TriState.OFF) continue
        if (attributeOf(entry, key, homeCollectionId) != (state == TriState.INCLUDE)) return false
    }
    return true
}

class GalleryIndex(private val homeCollectionId: String) {
    private val entries = LinkedHashMap<String, Stored>()
    private val byTag = HashMap<String, MutableSet<String>>()
    private val tagCounts = LinkedHashMap<String, Int>()
    private val words = HashMap<String, MutableSet<String>>()
    private val byMonthDay = HashMap<String, MutableSet<String>>()
    private var orderedIds: List<String> = emptyList()
    private var sortedWords: List<String>? = null
    private var dirty = false
    private var currentVersion = 0

    private val masterOrder: (String, String) -> Int = { leftId, rightId ->
        val byTime = sortResultOf((entries[rightId]?.takenAtMs ?: 0.0) - (entries[leftId]?.takenAtMs ?: 0.0))
        when {
            byTime != 0 -> byTime
            leftId == rightId -> 0
            leftId < rightId -> SORT_BEFORE
            else -> SORT_AFTER
        }
    }

    private fun ordered(): List<String> {
        if (dirty) {
            orderedIds = jsStableSort(entries.keys, masterOrder)
            dirty = false
        }
        return orderedIds
    }

    private fun wordKeys(): List<String> = sortedWords ?: jsStableSort(words.keys, String::compareTo).also { sortedWords = it }

    private fun inScope(entry: IndexedEntry, scope: GalleryScope): Boolean {
        val collectionId = when (scope) {
            GalleryScope.Home -> homeCollectionId
            is GalleryScope.Collection -> scope.collectionId
        }
        return collectionId in entry.collectionIds
    }

    private fun detach(entryId: String): Boolean {
        val stored = entries[entryId] ?: return false
        for (tag in stored.indexedTags) {
            removeFrom(byTag, tag, entryId)
            if (stored.counted) {
                val count = (tagCounts[tag] ?: 0) - 1
                if (count > 0) tagCounts[tag] = count else tagCounts.remove(tag)
            }
        }
        for (token in stored.tokens) {
            val before = words.size
            removeFrom(words, token, entryId)
            if (words.size != before) sortedWords = null
        }
        removeFrom(byMonthDay, stored.entry.monthDay, entryId)
        entries.remove(entryId)
        dirty = true
        return true
    }

    fun upsert(entry: IndexedEntry, noteText: String) {
        detach(entry.id)
        val indexedTags = if (entry.unreadable) emptyList() else entry.tags.distinct()
        val tokens = if (entry.unreadable) emptyList() else tokenize(noteText)
        val counted = entry.isOwn && !entry.trashed && !entry.unreadable
        entries[entry.id] = Stored(entry, JsDate.parse(entry.takenAtUtc), tokens, indexedTags, counted)
        for (tag in indexedTags) {
            addTo(byTag, tag, entry.id)
            if (counted) tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
        }
        for (token in tokens) {
            if (token !in words) sortedWords = null
            addTo(words, token, entry.id)
        }
        addTo(byMonthDay, entry.monthDay, entry.id)
        dirty = true
        currentVersion += 1
    }

    fun remove(entryId: String) {
        if (detach(entryId)) currentVersion += 1
    }

    fun clear() {
        entries.clear()
        byTag.clear()
        tagCounts.clear()
        words.clear()
        byMonthDay.clear()
        orderedIds = emptyList()
        sortedWords = null
        dirty = false
        currentVersion += 1
    }

    fun get(entryId: String): IndexedEntry? = entries[entryId]?.entry

    fun size(): Int = entries.size

    fun version(): Int = currentVersion

    private fun idsWithPrefix(token: String): Set<String> {
        val keys = wordKeys()
        val matches = HashSet<String>()
        for (position in lowerBound(keys, token) until keys.size) {
            val key = keys[position]
            if (!key.startsWith(token)) break
            words[key]?.let { matches.addAll(it) }
        }
        return matches
    }

    private fun candidates(filter: GalleryFilter): Set<String>? {
        val sets = filter.tags.map { byTag[it] ?: emptySet() }.toMutableList()
        for (token in tokenize(filter.text)) sets.add(idsWithPrefix(token))
        return if (sets.isEmpty()) null else intersect(sets)
    }

    private fun matches(entry: IndexedEntry, filter: GalleryFilter): Boolean {
        if (!inScope(entry, filter.scope)) return false
        if (filter.attributes != null && !attributesMatch(entry, filter.attributes, homeCollectionId)) return false
        if (entry.trashed != (filter.view == GalleryView.TRASH)) return false
        if (filter.fromDate != null && entry.localDate < filter.fromDate) return false
        return filter.toDate == null || entry.localDate <= filter.toDate
    }

    fun query(filter: GalleryFilter): List<DaySection> {
        val allowed = candidates(filter)
        val sections = mutableListOf<Pair<String, MutableList<String>>>()
        for (id in ordered()) {
            if (allowed != null && id !in allowed) continue
            val entry = entries[id]?.entry
            if (entry == null || !matches(entry, filter)) continue
            val last = sections.lastOrNull()
            if (last != null && last.first == entry.localDate) last.second.add(id) else sections.add(entry.localDate to mutableListOf(id))
        }
        return sections.map { (localDate, entryIds) -> DaySection(localDate, entryIds) }
    }

    fun allTags(): List<TagCount> =
        jsStableSort(tagCounts.entries.filter { it.value > 0 }) { left, right -> if (left.key < right.key) SORT_BEFORE else SORT_AFTER }
            .map { TagCount(it.key, it.value) }

    fun tagSuggestions(prefix: String, limit: Int): List<String> {
        val normalized = normalizedPrefix(prefix) ?: return emptyList()
        val ranked = jsStableSort(tagCounts.entries.filter { it.key.startsWith(normalized) }) { left, right ->
            when {
                left.value != right.value -> right.value - left.value
                left.key < right.key -> SORT_BEFORE
                else -> SORT_AFTER
            }
        }
        return ranked.subList(0, sliceEnd(ranked.size, limit)).map { it.key }
    }

    fun onThisDay(todayLocalDate: String, scope: GalleryScope): List<OnThisDayGroup> {
        val todayYear = yearOf(todayLocalDate)
        val groups = LinkedHashMap<Double, MutableList<String>>()
        val ids = jsStableSort(byMonthDay[todayLocalDate.drop(MONTH_DAY_START)].orEmpty(), masterOrder)
        for (id in ids) {
            val entry = entries[id]?.entry
            if (entry == null || entry.trashed || !inScope(entry, scope)) continue
            val yearsAgo = todayYear - yearOf(entry.localDate)
            if (yearsAgo < MIN_YEARS_AGO) continue
            groups.getOrPut(yearsAgo) { mutableListOf() }.add(id)
        }
        return jsStableSort(groups.entries) { left, right -> sortResultOf(left.key - right.key) }
            .map { OnThisDayGroup(it.key, it.value.toList()) }
    }

    fun daysWithEntries(yearMonth: String, scope: GalleryScope): Map<String, String> {
        val prefix = yearMonth + LOCAL_DATE_SEPARATOR
        val covers = LinkedHashMap<String, String>()
        for (id in ordered()) {
            val entry = entries[id]?.entry
            if (entry == null || entry.trashed || !entry.localDate.startsWith(prefix)) continue
            if (!inScope(entry, scope) || entry.localDate in covers) continue
            covers[entry.localDate] = id
        }
        return covers
    }
}

package dev.kodakiko.ui.core.settings

import androidx.compose.runtime.Immutable
import dev.kodakiko.js.isJsWhitespace
import dev.kodakiko.js.jsTrim
import dev.kodakiko.ui.core.navigation.Route

@Immutable
data class SettingsSection(
    val title: String,
    val route: Route,
    val preferences: List<Preference>,
)

@Immutable
data class SettingsIndexEntry(
    val title: String,
    val section: String,
    val route: Route,
    val breadcrumbs: String,
    val highlightKey: String?,
)

fun settingsIndex(sections: List<SettingsSection>): List<SettingsIndexEntry> = sections.flatMap { section ->
    val heading = SettingsIndexEntry(
        title = section.title,
        section = section.title,
        route = section.route,
        breadcrumbs = section.title,
        highlightKey = null,
    )
    listOf(heading) + section.preferences.filter { it.visible }.flatMap { preference ->
        when (preference) {
            is Preference.PreferenceGroup ->
                preference.preferenceItems
                    .filter { it.searchable() }
                    .map { section.entry(it.title, preference.title) }

            is Preference.PreferenceItem -> if (preference.searchable()) listOf(section.entry(preference.title, null)) else emptyList()
        }
    }
}

fun searchSettings(sections: List<SettingsSection>, query: String): List<SettingsIndexEntry> {
    val words = searchWords(query)
    if (words.isEmpty()) return emptyList()
    return settingsIndex(sections).filter { entry ->
        val haystack = "${entry.title} ${entry.section}".replace(SettingsConstants.ELLIPSIS.toString(), "").lowercase()
        words.all { haystack.contains(it) }
    }
}

internal fun searchWords(query: String): List<String> {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    for (char in jsTrim(query).lowercase()) {
        if (isJsWhitespace(char)) {
            if (current.isNotEmpty()) words += current.toString()
            current.clear()
        } else {
            current.append(char)
        }
    }
    if (current.isNotEmpty()) words += current.toString()
    return words
}

private fun Preference.PreferenceItem.searchable(): Boolean =
    visible && title.isNotBlank() && this !is Preference.PreferenceItem.InfoPreference

private fun SettingsSection.entry(title: String, group: String?): SettingsIndexEntry = SettingsIndexEntry(
    title = title,
    section = this.title,
    route = route,
    breadcrumbs = if (group == null) this.title else this.title + SettingsConstants.BREADCRUMB_SEPARATOR + group,
    highlightKey = title,
)

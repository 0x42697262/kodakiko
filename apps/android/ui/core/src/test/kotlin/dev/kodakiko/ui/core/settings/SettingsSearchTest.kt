package dev.kodakiko.ui.core.settings

import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsSearchTest {
    private val sections by lazy { V031SettingsIndex.sections() }

    private fun search(query: String): List<SettingsIndexEntry> = searchSettings(sections, query)

    @Test
    fun findsItemsAcrossScreensCaseInsensitivelyWithTheScreenToOpen() {
        val results = search("THEME")
        assertTrue(results.map { it.route }.contains(Route.SettingsAppearance))
        assertEquals(Route.SettingsSecurity, search("secure screen").firstOrNull()?.route)
        val perRow = search("items per row")
        assertTrue(perRow.isNotEmpty())
        assertTrue(perRow.all { it.route == Route.SettingsGallery })
    }

    @Test
    fun matchesEveryWordInAnyOrderAndIgnoresBlankQueries() {
        assertTrue(search("upload large").map { it.route }.contains(Route.SettingsStorage))
        assertEquals(emptyList<SettingsIndexEntry>(), search("   "))
    }

    @Test
    fun hasAnEntryForEverySettingsScreen() {
        val routes = settingsIndex(sections).map { it.route }.toSet()
        for (route in listOf(
            Route.SettingsAppearance,
            Route.SettingsGallery,
            Route.SettingsCapture,
            Route.SettingsSecurity,
            Route.SettingsStorage,
            Route.SettingsSync,
            Route.SettingsAccount,
            Route.SettingsAbout,
        )) {
            assertTrue(route.path, routes.contains(route))
        }
    }

    @Test
    fun theIndexListsEachSectionTitleFirstAndThenItsItemsInOrder() {
        val index = settingsIndex(sections)
        val sync = index.filter { it.route == Route.SettingsSync }
        assertEquals(
            listOf(string(R.string.settings_row_sync), string(R.string.settings_sync_now), string(R.string.settings_retry_failed)),
            sync.map { it.title },
        )
        assertTrue(sync.all { it.section == string(R.string.settings_row_sync) })
        assertEquals(sections.sumOf { it.preferences.size + 1 }, index.size)
    }

    @Test
    fun resultsKeepTheIndexOrderAndSearchTitleAndSectionTogether() {
        val results = search("security")
        assertEquals(
            listOf(
                string(R.string.prefs_security),
                string(R.string.settings_auto_lock),
                string(R.string.settings_biometric),
                string(R.string.settings_encrypt_thumbnails),
                string(R.string.prefs_secure_screen),
                string(R.string.prefs_hide_note_previews),
                string(R.string.settings_security_code),
            ),
            results.map { it.title },
        )
        assertEquals(
            listOf(string(R.string.prefs_secure_screen)),
            search("screen privacy").map { it.title },
        )
    }

    @Test
    fun theEllipsisInATitleIsNotSearchable() {
        assertEquals(listOf(string(R.string.settings_change_password)), search("change password").map { it.title })
        assertEquals(emptyList<SettingsIndexEntry>(), search("\u2026"))
    }

    @Test
    fun aNonMatchingWordRemovesTheEntry() {
        assertEquals(emptyList<SettingsIndexEntry>(), search("theme zebra"))
        assertEquals(emptyList<SettingsIndexEntry>(), search("nothing-matches-this"))
    }

    @Test
    fun breadcrumbsNameTheScreenAndItemsHighlightThemselves() {
        val secure = search("secure screen").single()
        assertEquals(string(R.string.prefs_secure_screen), secure.title)
        assertEquals(string(R.string.prefs_security), secure.section)
        assertEquals(string(R.string.prefs_security), secure.breadcrumbs)
        assertEquals(string(R.string.prefs_secure_screen), secure.highlightKey)
        val screen = settingsIndex(sections).first { it.route == Route.SettingsAbout }
        assertEquals(string(R.string.settings_row_about), screen.title)
        assertEquals(string(R.string.settings_row_about), screen.breadcrumbs)
        assertNull(screen.highlightKey)
    }

    @Test
    fun itemsInsideAGroupCarryTheGroupInTheirBreadcrumbs() {
        val grouped = listOf(
            SettingsSection(
                title = string(R.string.prefs_gallery),
                route = Route.SettingsGallery,
                preferences = listOf(
                    Preference.PreferenceGroup(
                        title = string(R.string.prefs_badges),
                        preferenceItems = listOf(
                            Preference.PreferenceItem.SwitchPreference(
                                value = true,
                                title = string(R.string.prefs_badge_video),
                                onValueChanged = {},
                            ),
                        ),
                    ),
                    Preference.PreferenceItem.TextPreference(title = string(R.string.prefs_display_mode)),
                ),
            ),
        )
        val video = searchSettings(grouped, "video").single()
        assertEquals(string(R.string.prefs_badge_video), video.title)
        assertEquals(string(R.string.prefs_gallery), video.section)
        assertEquals("${string(R.string.prefs_gallery)} > ${string(R.string.prefs_badges)}", video.breadcrumbs)
        assertEquals(string(R.string.prefs_badge_video), video.highlightKey)
        val display = searchSettings(grouped, "display").single()
        assertEquals(string(R.string.prefs_gallery), display.breadcrumbs)
        assertEquals(
            listOf(string(R.string.prefs_gallery), string(R.string.prefs_badge_video), string(R.string.prefs_display_mode)),
            settingsIndex(grouped).map { it.title },
        )
    }

    @Test
    fun hiddenItemsHiddenGroupsInfoTextAndBlankTitlesAreNotIndexed() {
        val section = SettingsSection(
            title = string(R.string.prefs_security),
            route = Route.SettingsSecurity,
            preferences = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    value = true,
                    title = string(R.string.prefs_secure_screen),
                    visible = false,
                    onValueChanged = {},
                ),
                Preference.PreferenceGroup(
                    title = string(R.string.prefs_badges),
                    visible = false,
                    preferenceItems = listOf(Preference.PreferenceItem.TextPreference(title = string(R.string.prefs_badge_note))),
                ),
                Preference.PreferenceItem.InfoPreference(title = string(R.string.prefs_full_screen_summary)),
                Preference.PreferenceItem.TextPreference(title = ""),
                Preference.PreferenceItem.TextPreference(title = string(R.string.settings_auto_lock)),
            ),
        )
        val index = settingsIndex(listOf(section))
        assertEquals(listOf(string(R.string.prefs_security), string(R.string.settings_auto_lock)), index.map { it.title })
        assertFalse(searchSettings(listOf(section), "secure screen").any { it.title == string(R.string.prefs_secure_screen) })
        assertEquals(emptyList<SettingsIndexEntry>(), searchSettings(listOf(section), "note"))
        assertEquals(emptyList<SettingsIndexEntry>(), searchSettings(listOf(section), "swipe"))
    }
}

package dev.kodakiko.ui.feature.settings

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.settings.Preference
import dev.kodakiko.ui.core.settings.SettingsIndexEntry
import dev.kodakiko.ui.core.settings.SettingsSection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

private class IndexedSection(val title: Int, val route: Route, val items: List<Int>)

private val SEARCH_INDEX: List<IndexedSection> = listOf(
    IndexedSection(
        R.string.prefs_appearance,
        Route.SettingsAppearance,
        listOf(
            R.string.prefs_theme,
            R.string.prefs_date_format,
            R.string.prefs_relative_dates,
            R.string.prefs_start_screen,
            R.string.prefs_full_screen,
            R.string.prefs_hide_bars_on_scroll,
            R.string.prefs_pure_black,
            R.string.prefs_time_format,
            R.string.prefs_haptics,
            R.string.prefs_dynamic_color,
        ),
    ),
    IndexedSection(
        R.string.advanced_title,
        Route.SettingsAdvanced,
        listOf(R.string.prefs_technical_details, R.string.advanced_rebuild, R.string.advanced_reset, R.string.advanced_debug),
    ),
    IndexedSection(
        R.string.prefs_gallery,
        Route.SettingsGallery,
        listOf(
            R.string.prefs_display_mode,
            R.string.prefs_columns_portrait,
            R.string.prefs_columns_landscape,
            R.string.prefs_group_by_day,
            R.string.prefs_badges,
            R.string.prefs_show_tag_tabs,
            R.string.prefs_show_counts,
            R.string.prefs_on_this_day,
            R.string.prefs_confirm_trash,
            R.string.prefs_hide_shared,
        ),
    ),
    IndexedSection(
        R.string.prefs_capture,
        Route.SettingsCapture,
        listOf(
            R.string.settings_location,
            R.string.prefs_start_camera,
            R.string.prefs_keep_screen_on,
            R.string.prefs_camera_app,
            R.string.prefs_hold_to_record,
            R.string.prefs_hold_max,
        ),
    ),
    IndexedSection(
        R.string.prefs_security,
        Route.SettingsSecurity,
        listOf(
            R.string.settings_auto_lock,
            R.string.settings_biometric,
            R.string.settings_encrypt_thumbnails,
            R.string.prefs_secure_screen,
            R.string.prefs_hide_note_previews,
        ),
    ),
    IndexedSection(
        R.string.prefs_data_storage,
        Route.SettingsStorage,
        listOf(
            R.string.settings_cache_cap,
            R.string.settings_clear_originals,
            R.string.settings_wifi_only,
            R.string.settings_large_means,
            R.string.prefs_export_journal,
            R.string.prefs_autoplay,
            R.string.prefs_autoplay_mobile,
        ),
    ),
    IndexedSection(R.string.settings_row_sync, Route.SettingsSync, listOf(R.string.settings_sync_now, R.string.settings_retry_failed)),
    IndexedSection(
        R.string.settings_row_account,
        Route.SettingsAccount,
        listOf(
            R.string.settings_change_password,
            R.string.settings_show_recovery,
            R.string.settings_delete_account,
            R.string.settings_security_code,
            R.string.server_title,
        ),
    ),
    IndexedSection(R.string.settings_row_about, Route.SettingsAbout, listOf(R.string.settings_app_version, R.string.settings_licenses)),
)

fun settingsSearchSections(resources: Resources): List<SettingsSection> = SEARCH_INDEX.map { section ->
    SettingsSection(
        title = resources.getString(section.title),
        route = section.route,
        preferences = section.items.map { Preference.PreferenceItem.TextPreference(title = resources.getString(it)) },
    )
}

sealed interface SettingsSearchAction {
    data class Open(val entry: SettingsIndexEntry) : SettingsSearchAction

    data object Back : SettingsSearchAction
}

class SettingsSearchViewModel(private val deps: SettingsDeps) : ViewModel() {
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    fun onAction(action: SettingsSearchAction) {
        when (action) {
            is SettingsSearchAction.Open -> {
                deps.highlight.request(action.entry.route, action.entry.highlightKey)
                channel.trySend(SettingsEvent.Replace(action.entry.route))
            }
            SettingsSearchAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }
}

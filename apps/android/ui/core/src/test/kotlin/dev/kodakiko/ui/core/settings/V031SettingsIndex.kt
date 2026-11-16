package dev.kodakiko.ui.core.settings

import androidx.annotation.StringRes
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.string

object V031SettingsIndex {
    private fun section(@StringRes title: Int, route: Route, vararg items: Int): SettingsSection =
        SettingsSection(
            title = string(title),
            route = route,
            preferences = items.map { Preference.PreferenceItem.TextPreference(title = string(it)) },
        )

    fun sections(): List<SettingsSection> = listOf(
        section(
            R.string.prefs_appearance,
            Route.SettingsAppearance,
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
        section(
            R.string.advanced_title,
            Route.SettingsAdvanced,
            R.string.prefs_technical_details,
            R.string.advanced_rebuild,
            R.string.advanced_reset,
            R.string.advanced_debug,
        ),
        section(
            R.string.prefs_gallery,
            Route.SettingsGallery,
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
        section(
            R.string.prefs_capture,
            Route.SettingsCapture,
            R.string.settings_location,
            R.string.prefs_start_camera,
            R.string.prefs_keep_screen_on,
            R.string.prefs_camera_app,
            R.string.prefs_hold_to_record,
            R.string.prefs_hold_max,
        ),
        section(
            R.string.prefs_security,
            Route.SettingsSecurity,
            R.string.settings_auto_lock,
            R.string.settings_biometric,
            R.string.settings_encrypt_thumbnails,
            R.string.prefs_secure_screen,
            R.string.prefs_hide_note_previews,
        ),
        section(
            R.string.prefs_data_storage,
            Route.SettingsStorage,
            R.string.settings_cache_cap,
            R.string.settings_clear_originals,
            R.string.settings_wifi_only,
            R.string.settings_large_means,
            R.string.prefs_export_journal,
            R.string.prefs_autoplay,
            R.string.prefs_autoplay_mobile,
        ),
        section(
            R.string.settings_row_sync,
            Route.SettingsSync,
            R.string.settings_sync_now,
            R.string.settings_retry_failed,
        ),
        section(
            R.string.settings_row_account,
            Route.SettingsAccount,
            R.string.settings_change_password,
            R.string.settings_show_recovery,
            R.string.settings_delete_account,
            R.string.settings_security_code,
            R.string.server_title,
        ),
        section(
            R.string.settings_row_about,
            Route.SettingsAbout,
            R.string.settings_app_version,
            R.string.settings_licenses,
        ),
    )
}

// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.CameraApp
import dev.kodakiko.data.db.CameraSide
import dev.kodakiko.data.db.DateFormat
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.GalleryBadges
import dev.kodakiko.data.db.GalleryDisplayMode
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.data.db.TimeFormat
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.settings.Preference
import dev.kodakiko.ui.core.settings.PreferenceScaffold
import dev.kodakiko.ui.core.settings.widget.ThemePresetPreferenceWidget
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun AppearanceSettingsScreen(viewModel: AppearanceSettingsViewModel = metroViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    PreferenceScaffold(
        title = stringResource(R.string.prefs_appearance),
        items = appearanceItems(settings, viewModel.dynamicColorAvailable, viewModel::update),
        modifier = Modifier.testTag(TestTags.Settings.APPEARANCE_SCREEN),
        onBackPressed = viewModel::back,
        highlightKey = viewModel.highlightKey,
        navigateUpTestTag = TestTags.Settings.BACK,
    )
}

@Composable
private fun appearanceItems(settings: DeviceSettings, dynamicColorAvailable: Boolean, update: SettingsChange): List<Preference> = listOf(
    Preference.PreferenceItem.SwitchPreference(
        value = settings.fullScreen,
        title = stringResource(R.string.prefs_full_screen),
        subtitle = stringResource(R.string.prefs_full_screen_summary),
        testTag = TestTags.Settings.FULL_SCREEN,
        onValueChanged = { on -> update { it.copy(fullScreen = on) } },
    ),
    Preference.PreferenceItem.SwitchPreference(
        value = settings.hideBarsOnScroll,
        title = stringResource(R.string.prefs_hide_bars_on_scroll),
        subtitle = stringResource(R.string.prefs_hide_bars_on_scroll_summary),
        testTag = TestTags.Settings.HIDE_BARS_ON_SCROLL,
        onValueChanged = { on -> update { it.copy(hideBarsOnScroll = on) } },
    ),
    Preference.PreferenceItem.ListPreference(
        value = settings.appTheme,
        entries = mapOf(
            AppTheme.SYSTEM to stringResource(R.string.prefs_theme_system),
            AppTheme.LIGHT to stringResource(R.string.prefs_theme_light),
            AppTheme.DARK to stringResource(R.string.prefs_theme_dark),
        ),
        title = stringResource(R.string.prefs_theme),
        testTag = TestTags.Settings.THEME,
        onValueChanged = { theme -> update { it.copy(appTheme = theme) } },
    ),
    Preference.PreferenceItem.CustomPreference(title = stringResource(R.string.theme_presets_title), visible = !settings.dynamicColor) {
        ThemePresetPreferenceWidget(
            selected = settings.themePreset,
            appTheme = settings.appTheme,
            pureBlack = settings.pureBlack,
            onSelect = { preset -> update { it.copy(themePreset = preset) } },
        )
    },
    Preference.PreferenceItem.SwitchPreference(
        value = settings.hapticFeedback,
        title = stringResource(R.string.prefs_haptics),
        subtitle = stringResource(R.string.prefs_haptics_summary),
        testTag = TestTags.Settings.HAPTICS,
        onValueChanged = { on -> update { it.copy(hapticFeedback = on) } },
    ),
    Preference.PreferenceItem.SwitchPreference(
        value = settings.dynamicColor,
        title = stringResource(R.string.prefs_dynamic_color),
        subtitle = stringResource(R.string.prefs_dynamic_color_summary),
        testTag = TestTags.Settings.DYNAMIC_COLOR,
        visible = dynamicColorAvailable,
        onValueChanged = { on -> update { it.copy(dynamicColor = on) } },
    ),
    Preference.PreferenceItem.SwitchPreference(
        value = settings.pureBlack,
        title = stringResource(R.string.prefs_pure_black),
        subtitle = stringResource(R.string.prefs_pure_black_summary),
        testTag = TestTags.Settings.PURE_BLACK,
        onValueChanged = { on -> update { it.copy(pureBlack = on) } },
    ),
    Preference.PreferenceItem.ListPreference(
        value = settings.timeFormat,
        entries = mapOf(
            TimeFormat.HOURS_24 to stringResource(R.string.prefs_time24),
            TimeFormat.HOURS_12 to stringResource(R.string.prefs_time12),
        ),
        title = stringResource(R.string.prefs_time_format),
        testTag = TestTags.Settings.TIME_FORMAT,
        onValueChanged = { format -> update { it.copy(timeFormat = format) } },
    ),
    Preference.PreferenceItem.ListPreference(
        value = settings.dateFormat,
        entries = mapOf(
            DateFormat.SYSTEM to stringResource(R.string.prefs_date_system),
            DateFormat.ISO to stringResource(R.string.prefs_date_iso),
            DateFormat.DMY to stringResource(R.string.prefs_date_dmy),
            DateFormat.MDY to stringResource(R.string.prefs_date_mdy),
        ),
        title = stringResource(R.string.prefs_date_format),
        testTag = TestTags.Settings.DATE_FORMAT,
        onValueChanged = { format -> update { it.copy(dateFormat = format) } },
    ),
    Preference.PreferenceItem.SwitchPreference(
        value = settings.relativeDates,
        title = stringResource(R.string.prefs_relative_dates),
        subtitle = stringResource(R.string.prefs_relative_dates_summary),
        testTag = TestTags.Settings.RELATIVE_DATES,
        onValueChanged = { on -> update { it.copy(relativeDates = on) } },
    ),
    Preference.PreferenceItem.ListPreference(
        value = settings.startScreen,
        entries = mapOf(
            StartScreen.GALLERY to stringResource(R.string.tabs_gallery),
            StartScreen.CALENDAR to stringResource(R.string.tabs_calendar),
        ),
        title = stringResource(R.string.prefs_start_screen),
        testTag = TestTags.Settings.START_SCREEN,
        onValueChanged = { screen -> update { it.copy(startScreen = screen) } },
    ),
)

@Composable
fun GallerySettingsScreen(viewModel: GallerySettingsViewModel = metroViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    PreferenceScaffold(
        title = stringResource(R.string.prefs_gallery),
        items = galleryItems(settings, viewModel::update),
        modifier = Modifier.testTag(TestTags.Settings.GALLERY_SCREEN),
        onBackPressed = viewModel::back,
        highlightKey = viewModel.highlightKey,
        navigateUpTestTag = TestTags.Settings.BACK,
    )
}

@Composable
private fun galleryItems(settings: DeviceSettings, update: SettingsChange): List<Preference> {
    val columns = (SettingsConstants.GALLERY_COLUMNS_AUTO..SettingsConstants.GALLERY_COLUMNS_MAX).associateWith { count ->
        if (count == SettingsConstants.GALLERY_COLUMNS_AUTO) stringResource(R.string.prefs_columns_auto) else count.toString()
    }
    val badges = settings.galleryBadges
    val badge: @Composable (String, Int, Boolean, (GalleryBadges, Boolean) -> GalleryBadges) -> Preference.PreferenceItem = { key, title, value, change ->
        Preference.PreferenceItem.SwitchPreference(
            value = value,
            title = stringResource(title),
            testTag = TestTags.Settings.BADGE + SettingsConstants.BADGE_TAG_SEPARATOR + key,
            onValueChanged = { on -> update { it.copy(galleryBadges = change(it.galleryBadges, on)) } },
        )
    }
    return listOf(
        Preference.PreferenceItem.ListPreference(
            value = settings.galleryDisplayMode,
            entries = mapOf(
                GalleryDisplayMode.COMFORTABLE_GRID to stringResource(R.string.prefs_mode_comfortable),
                GalleryDisplayMode.COMPACT_GRID to stringResource(R.string.prefs_mode_compact),
                GalleryDisplayMode.PHOTO_GRID to stringResource(R.string.prefs_mode_photo),
                GalleryDisplayMode.LIST to stringResource(R.string.prefs_mode_list),
            ),
            title = stringResource(R.string.prefs_display_mode),
            testTag = TestTags.Settings.DISPLAY_MODE,
            onValueChanged = { mode -> update { it.copy(galleryDisplayMode = mode) } },
        ),
        Preference.PreferenceItem.ListPreference(
            value = settings.galleryColumnsPortrait,
            entries = columns,
            title = stringResource(R.string.prefs_columns_portrait),
            testTag = TestTags.Settings.COLUMNS_PORTRAIT,
            onValueChanged = { count -> update { it.copy(galleryColumnsPortrait = count) } },
        ),
        Preference.PreferenceItem.ListPreference(
            value = settings.galleryColumnsLandscape,
            entries = columns,
            title = stringResource(R.string.prefs_columns_landscape),
            testTag = TestTags.Settings.COLUMNS_LANDSCAPE,
            onValueChanged = { count -> update { it.copy(galleryColumnsLandscape = count) } },
        ),
        Preference.PreferenceItem.SwitchPreference(
            value = settings.galleryGroupByDay,
            title = stringResource(R.string.prefs_group_by_day),
            testTag = TestTags.Settings.GROUP_BY_DAY,
            onValueChanged = { on -> update { it.copy(galleryGroupByDay = on) } },
        ),
        Preference.PreferenceGroup(
            title = stringResource(R.string.prefs_badges),
            preferenceItems = listOf(
                badge(BadgeKeys.VIDEO, R.string.prefs_badge_video, badges.video) { current, on -> current.copy(video = on) },
                badge(BadgeKeys.PHOTO_COUNT, R.string.prefs_badge_photo_count, badges.photoCount) { current, on -> current.copy(photoCount = on) },
                badge(BadgeKeys.NOTE, R.string.prefs_badge_note, badges.note) { current, on -> current.copy(note = on) },
                badge(BadgeKeys.PENDING, R.string.prefs_badge_pending, badges.pending) { current, on -> current.copy(pending = on) },
                badge(BadgeKeys.KEPT_OFFLINE, R.string.prefs_badge_kept_offline, badges.keptOffline) { current, on -> current.copy(keptOffline = on) },
                badge(BadgeKeys.FAVORITE, R.string.prefs_badge_favorite, badges.favorite) { current, on -> current.copy(favorite = on) },
            ),
        ),
        Preference.PreferenceGroup(
            title = stringResource(R.string.prefs_tabs),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    value = settings.galleryShowTagTabs,
                    title = stringResource(R.string.prefs_show_tag_tabs),
                    testTag = TestTags.Settings.SHOW_TAG_TABS,
                    onValueChanged = { on -> update { it.copy(galleryShowTagTabs = on) } },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    value = settings.galleryShowCounts,
                    title = stringResource(R.string.prefs_show_counts),
                    testTag = TestTags.Settings.SHOW_COUNTS,
                    onValueChanged = { on -> update { it.copy(galleryShowCounts = on) } },
                ),
            ),
        ),
        Preference.PreferenceGroup(
            title = stringResource(R.string.prefs_gallery),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    value = settings.onThisDay,
                    title = stringResource(R.string.prefs_on_this_day),
                    testTag = TestTags.Settings.ON_THIS_DAY,
                    onValueChanged = { on -> update { it.copy(onThisDay = on) } },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    value = settings.confirmTrash,
                    title = stringResource(R.string.prefs_confirm_trash),
                    testTag = TestTags.Settings.CONFIRM_TRASH,
                    onValueChanged = { on -> update { it.copy(confirmTrash = on) } },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    value = settings.hideShared,
                    title = stringResource(R.string.prefs_hide_shared),
                    testTag = TestTags.Settings.HIDE_SHARED,
                    onValueChanged = { on -> update { it.copy(hideShared = on) } },
                ),
            ),
        ),
    )
}

private object BadgeKeys {
    const val VIDEO = "video"
    const val PHOTO_COUNT = "photoCount"
    const val NOTE = "note"
    const val PENDING = "pending"
    const val KEPT_OFFLINE = "keptOffline"
    const val FAVORITE = "favorite"
}

@Composable
fun CaptureSettingsScreen(viewModel: CaptureSettingsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    PreferenceScaffold(
        title = stringResource(R.string.prefs_capture),
        items = captureItems(state, viewModel::onAction, viewModel::update),
        modifier = Modifier.testTag(TestTags.Settings.CAPTURE_SCREEN),
        onBackPressed = { viewModel.onAction(CaptureSettingsAction.Back) },
        highlightKey = viewModel.highlightKey,
        navigateUpTestTag = TestTags.Settings.BACK,
    )
}

@Composable
private fun captureItems(state: CaptureSettingsState, onAction: (CaptureSettingsAction) -> Unit, update: SettingsChange): List<Preference> {
    val settings = state.settings
    val deniedText = stringResource(R.string.settings_location_denied)
    val systemCamera = stringResource(R.string.prefs_camera_system)
    val kodakikoCamera = stringResource(R.string.prefs_camera_kodakiko)
    val cameraSummary = if (settings.cameraApp == CameraApp.SYSTEM) {
        systemCamera + SettingsConstants.SUMMARY_DASH + stringResource(R.string.prefs_camera_system_warning)
    } else {
        kodakikoCamera
    }
    return listOf(
        Preference.PreferenceItem.CustomPreference(title = "", visible = state.locationDenied) {
            Banner(
                text = deniedText,
                kind = BannerKind.WARNING,
                testTag = TestTags.Settings.LOCATION_DENIED,
                modifier = Modifier.padding(SettingsDimens.SCREEN_PADDING),
            )
        },
        Preference.PreferenceItem.SwitchPreference(
            value = settings.locationForCaptures,
            title = stringResource(R.string.settings_location),
            testTag = TestTags.Settings.LOCATION,
            onValueChanged = { on -> onAction(CaptureSettingsAction.SetLocation(on)) },
        ),
        Preference.PreferenceItem.ListPreference(
            value = settings.captureStartCamera,
            entries = mapOf(
                CameraSide.BACK to stringResource(R.string.prefs_camera_back),
                CameraSide.FRONT to stringResource(R.string.prefs_camera_front),
            ),
            title = stringResource(R.string.prefs_start_camera),
            testTag = TestTags.Settings.START_CAMERA,
            onValueChanged = { side -> update { it.copy(captureStartCamera = side) } },
        ),
        Preference.PreferenceItem.ListPreference(
            value = settings.cameraApp,
            entries = mapOf(CameraApp.KODAKIKO to kodakikoCamera, CameraApp.SYSTEM to systemCamera),
            title = stringResource(R.string.prefs_camera_app),
            subtitle = cameraSummary,
            testTag = TestTags.Settings.CAMERA_APP,
            onValueChanged = { app -> update { it.copy(cameraApp = app) } },
        ),
        Preference.PreferenceItem.SwitchPreference(
            value = settings.holdToRecordOn,
            title = stringResource(R.string.prefs_hold_to_record),
            subtitle = stringResource(R.string.prefs_hold_to_record_summary),
            testTag = TestTags.Settings.HOLD_TO_RECORD,
            onValueChanged = { on -> update { it.copy(holdToRecordOn = on) } },
        ),
        Preference.PreferenceItem.ListPreference(
            value = settings.holdToRecordMaxSeconds,
            entries = SettingsConstants.HOLD_TO_RECORD_SECONDS.associateWith { stringResource(R.string.prefs_seconds, it) },
            title = stringResource(R.string.prefs_hold_max),
            testTag = TestTags.Settings.HOLD_MAX,
            onValueChanged = { seconds -> update { it.copy(holdToRecordMaxSeconds = seconds) } },
        ),
        Preference.PreferenceItem.SwitchPreference(
            value = settings.captureKeepScreenOn,
            title = stringResource(R.string.prefs_keep_screen_on),
            testTag = TestTags.Settings.KEEP_SCREEN_ON,
            onValueChanged = { on -> update { it.copy(captureKeepScreenOn = on) } },
        ),
        Preference.PreferenceItem.InfoPreference(title = stringResource(R.string.prefs_no_phone_gallery)),
    )
}

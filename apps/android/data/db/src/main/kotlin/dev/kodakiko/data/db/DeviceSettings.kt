package dev.kodakiko.data.db

enum class AppTheme(override val wire: String) : WireValue {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

enum class ThemePreset(override val wire: String) : WireValue {
    DEFAULT("default"),
    CATPPUCCIN("catppuccin"),
    GREEN_APPLE("greenApple"),
    LAVENDER("lavender"),
    MIDNIGHT_DUSK("midnightDusk"),
    NORD("nord"),
    STRAWBERRY("strawberry"),
    TAKO("tako"),
    TEAL_TURQUOISE("tealTurquoise"),
    TIDAL_WAVE("tidalWave"),
    TOKYO_NIGHT("tokyoNight"),
    YIN_YANG("yinYang"),
    YOTSUBA("yotsuba"),
    MONOCHROME("monochrome"),
}

enum class DateFormat(override val wire: String) : WireValue {
    SYSTEM("system"),
    ISO("iso"),
    DMY("dmy"),
    MDY("mdy"),
}

enum class StartScreen(override val wire: String) : WireValue {
    GALLERY("gallery"),
    CALENDAR("calendar"),
}

enum class GalleryDisplayMode(override val wire: String) : WireValue {
    COMFORTABLE_GRID("comfortableGrid"),
    COMPACT_GRID("compactGrid"),
    PHOTO_GRID("photoGrid"),
    LIST("list"),
}

enum class GalleryGroup(override val wire: String) : WireValue {
    DAY("day"),
    MONTH("month"),
    YEAR("year"),
    TAG("tag"),
    NONE("none"),
}

enum class GallerySortKey(override val wire: String) : WireValue {
    TAKEN_AT("takenAt"),
    NOTE_LENGTH("noteLength"),
    MEDIA_COUNT("mediaCount"),
    RANDOM("random"),
}

enum class TriState(override val wire: String) : WireValue {
    OFF("off"),
    INCLUDE("include"),
    EXCLUDE("exclude"),
}

enum class CameraSide(override val wire: String) : WireValue {
    BACK("back"),
    FRONT("front"),
}

enum class TimeFormat(override val wire: String) : WireValue {
    HOURS_24("24h"),
    HOURS_12("12h"),
}

enum class CameraApp(override val wire: String) : WireValue {
    KODAKIKO("kodakiko"),
    SYSTEM("system"),
}

data class GalleryBadges(
    val video: Boolean,
    val photoCount: Boolean,
    val note: Boolean,
    val pending: Boolean,
    val keptOffline: Boolean,
    val favorite: Boolean,
)

data class GallerySort(
    val key: GallerySortKey,
    val descending: Boolean,
)

data class GalleryFilters(
    val video: TriState,
    val note: TriState,
    val multiple: TriState,
    val shared: TriState,
    val pending: TriState,
    val keptOffline: TriState,
    val favorite: TriState,
)

data class DeviceSettings(
    val encryptThumbnails: Boolean,
    val originalCacheCapBytes: Long,
    val largeUploadsOnWifiOnly: Boolean,
    val largeUploadThresholdBytes: Long,
    val locationForCaptures: Boolean,
    val autoLockMinutes: Int,
    val appTheme: AppTheme,
    val dateFormat: DateFormat,
    val relativeDates: Boolean,
    val startScreen: StartScreen,
    val galleryDisplayMode: GalleryDisplayMode,
    val galleryColumnsPortrait: Int,
    val galleryColumnsLandscape: Int,
    val galleryGroupByDay: Boolean,
    val galleryGroupBy: GalleryGroup,
    val galleryBadges: GalleryBadges,
    val galleryShowTagTabs: Boolean,
    val galleryShowCounts: Boolean,
    val gallerySort: GallerySort,
    val galleryFilters: GalleryFilters,
    val hideShared: Boolean,
    val onThisDay: Boolean,
    val confirmTrash: Boolean,
    val captureStartCamera: CameraSide,
    val captureKeepScreenOn: Boolean,
    val hideNotePreviews: Boolean,
    val secureScreen: Boolean,
    val fullScreen: Boolean,
    val hideBarsOnScroll: Boolean,
    val showTechnicalDetails: Boolean,
    val holdToRecordOn: Boolean,
    val holdToRecordMaxSeconds: Int,
    val cameraApp: CameraApp,
    val syncPaused: Boolean,
    val tagOrder: List<String>,
    val pureBlack: Boolean,
    val hapticFeedback: Boolean,
    val dynamicColor: Boolean,
    val autoplayVideos: Boolean,
    val autoplayOnMobileData: Boolean,
    val timeFormat: TimeFormat,
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
)

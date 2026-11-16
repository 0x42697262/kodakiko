package dev.kodakiko.ui.feature.gallery

import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.GalleryBadges
import dev.kodakiko.data.db.GalleryDisplayMode
import dev.kodakiko.data.db.GalleryGroup

object GalleryStates {
    val defaults: DeviceSettings = DeviceSettingsDefaults.ANDROID

    fun tile(
        id: String,
        position: Int,
        localDate: String = "2026-11-18",
        time: String = "09:00",
        note: String = "",
        tags: List<String> = emptyList(),
        pending: Boolean = false,
        favorite: Boolean = false,
        hasVideo: Boolean = false,
        extraMedia: Int = 0,
        unreadable: Boolean = false,
        coverMediaId: String? = "media-$id",
    ): GalleryTile = GalleryTile(
        entryId = id,
        position = position,
        coverMediaId = coverMediaId,
        unreadable = unreadable,
        pending = pending,
        favorite = favorite,
        hasVideo = hasVideo,
        hasNote = note.isNotEmpty(),
        extraMedia = extraMedia,
        localDate = localDate,
        time = time,
        note = note,
        tags = tags,
    )

    fun display(
        mode: GalleryDisplayMode = GalleryDisplayMode.COMFORTABLE_GRID,
        columnsPortrait: Int = 0,
        columnsLandscape: Int = 0,
        badges: GalleryBadges = defaults.galleryBadges,
    ): GalleryDisplay = GalleryDisplay(mode, columnsPortrait, columnsLandscape, badges)

    val threeDays: List<GallerySection> = listOf(
        GallerySection("2026-11-18", listOf(tile("e1", 0, "2026-11-18", "09:00", "morning walk", listOf("a")))),
        GallerySection(
            "2026-11-17",
            listOf(tile("e2", 1, "2026-11-17", "10:00", "lake day", listOf("a", "b"), pending = true, favorite = true, hasVideo = true, extraMedia = 2)),
        ),
        GallerySection("2026-11-16", listOf(tile("e3", 2, "2026-11-16", "11:00", tags = listOf("c")))),
    )

    val oneDay: List<GallerySection> = listOf(
        GallerySection("2026-11-17", listOf(tile("e2", 0, "2026-11-17", "10:00", "lake day", listOf("a", "b"), pending = true, favorite = true, hasVideo = true, extraMedia = 2))),
    )

    val recentDays: List<GallerySection> = listOf(
        GallerySection(
            "2026-11-21",
            listOf(
                tile("t1", 0, "2026-11-21", "18:40", "Dinner with the whole family", listOf("family"), favorite = true, extraMedia = 3),
                tile("t2", 1, "2026-11-21", "12:05", "", pending = true),
                tile("t3", 2, "2026-11-21", "08:15", "Frost on the window", hasVideo = true),
                tile("t4", 3, "2026-11-21", "07:50", "", unreadable = true, coverMediaId = null),
            ),
        ),
        GallerySection(
            "2026-11-20",
            listOf(
                tile("t5", 4, "2026-11-20", "21:00", "Night walk", listOf("walk")),
                tile("t6", 5, "2026-11-20", "16:30", "", extraMedia = 1),
            ),
        ),
        GallerySection("2025-12-24", listOf(tile("t7", 6, "2025-12-24", "19:00", "Christmas eve", listOf("family", "holiday"), favorite = true))),
    )

    fun grid(
        sections: List<GallerySection> = threeDays,
        display: GalleryDisplay = display(),
        selection: GallerySelection = GallerySelection(),
        today: String = TODAY,
    ): GalleryGrid = GalleryGrid(sections = sections, today = today, display = display, selection = selection)

    val tabs: List<TagTab> = listOf(
        TagTab.All(7, true),
        TagTab.Favorites(2, false),
        TagTab.Tag(0, "family", 2, false),
        TagTab.Tag(1, "holiday", 1, false),
        TagTab.Tag(2, "walk", 1, false),
    )

    val onThisDay: List<OnThisDayYear> = listOf(
        OnThisDayYear(1, listOf(OnThisDayEntry("y1", "media-y1", false), OnThisDayEntry("y2", "media-y2", false))),
        OnThisDayYear(2, listOf(OnThisDayEntry("y3", null, true))),
    )

    fun gallery(
        grid: GalleryGrid = grid(),
        tagTabs: List<TagTab> = emptyList(),
        showCounts: Boolean = false,
        searching: Boolean = false,
        searchText: String = "",
        chips: List<FilterChip> = emptyList(),
        filterActive: Boolean = false,
        onThisDay: List<OnThisDayYear> = emptyList(),
        empty: GalleryEmpty? = null,
        opening: Boolean = false,
        pullToSync: Boolean = true,
        refreshing: Boolean = false,
        barsHidden: Boolean = false,
        sheet: GallerySheet? = null,
        snackbar: GallerySnackbar? = null,
    ): GalleryState = GalleryState(
        opening = opening,
        username = "alice",
        grid = grid,
        showCounts = showCounts,
        tagTabs = tagTabs,
        searching = searching,
        searchText = searchText,
        chips = chips,
        filterActive = filterActive,
        onThisDay = onThisDay,
        empty = empty,
        pullToSync = pullToSync,
        refreshing = refreshing,
        barsHidden = barsHidden,
        sheet = sheet,
        snackbar = snackbar,
    )

    fun settingsSheet(
        settings: DeviceSettings = defaults,
        group: GalleryGroup = GalleryGroup.DAY,
        tagChoices: List<String> = listOf("beach", "city", "family"),
        draft: FilterDraft = FilterDraft(),
    ): GallerySheet.Settings = GallerySheet.Settings(settings = settings, group = group, tagChoices = tagChoices, draft = draft)

    fun actions(
        mode: BulkMode = BulkMode.JOURNAL,
        panel: BulkPanel = BulkPanel.MENU,
        count: Int = 2,
        tagChoices: List<String> = listOf("beach", "city"),
        pickedTag: String? = null,
        failed: Boolean = false,
    ): GallerySheet.Actions =
        GallerySheet.Actions(BulkSheet(mode = mode, panel = panel, count = count, tagChoices = tagChoices, pickedTag = pickedTag, failed = failed))
}

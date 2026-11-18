package dev.kodakiko.app.di

import android.content.Context
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.app.gallery.AppDeviceSettings
import dev.kodakiko.app.gallery.AppGalleryAccount
import dev.kodakiko.app.viewer.AppVideoSource
import dev.kodakiko.app.viewer.AppViewerMedia
import dev.kodakiko.app.viewer.AppViewerVoice
import dev.kodakiko.app.viewer.CoilViewerImages
import dev.kodakiko.app.viewer.EntryOwners
import dev.kodakiko.app.viewer.SystemCalendarClock
import dev.kodakiko.app.viewer.SystemViewerClock
import dev.kodakiko.app.viewer.UnwiredCalendarSync
import dev.kodakiko.app.viewer.WifiState
import dev.kodakiko.data.net.NetworkMonitor
import dev.kodakiko.platform.device.Haptics
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.SystemEntriesClock
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.stats.DefaultStatsService
import dev.kodakiko.service.stats.StatsService
import dev.kodakiko.service.stats.StatsServiceDeps
import dev.kodakiko.ui.core.notice.GalleryNotice
import dev.kodakiko.ui.feature.calendar.CalendarDeps
import dev.kodakiko.ui.feature.calendar.CalendarThumbnails
import dev.kodakiko.ui.feature.gallery.GalleryFilterStore
import dev.kodakiko.ui.feature.gallery.GalleryFilters
import dev.kodakiko.ui.feature.gallery.GalleryThumbnails
import dev.kodakiko.ui.feature.viewer.EntryDetailsDeps
import dev.kodakiko.ui.feature.viewer.VideoPlayerDeps
import dev.kodakiko.ui.feature.viewer.ViewerDeps
import dev.kodakiko.ui.feature.viewer.ViewerImages
import dev.kodakiko.ui.feature.viewer.ViewerMedia
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher

class ViewerDepsFactory(
    private val context: Context,
    private val index: IndexService,
    private val entries: EntriesService,
    private val media: AccountMedia,
    private val viewerMedia: ViewerMedia,
    private val videos: AppVideoSource,
    private val settings: AppDeviceSettings,
    private val wifi: WifiState,
    private val account: AppGalleryAccount,
    private val config: ConfigService,
    private val owners: EntryOwners,
    private val notices: Observable<GalleryNotice?>,
    private val filter: GalleryFilterStore,
    private val ioDispatcher: CoroutineDispatcher,
) {
    fun create(): ViewerDeps = ViewerDeps(
        index = index,
        entries = entries,
        media = viewerMedia,
        videos = videos,
        voice = AppViewerVoice(context, media, ioDispatcher),
        settings = settings.settings,
        wifi = wifi.wifi,
        accountMode = account.mode,
        config = config,
        owners = owners::ownerOf,
        notices = notices,
        gallery = { tag -> filter.set(GalleryFilters.EMPTY.copy(tags = listOf(tag))) },
        clock = SystemViewerClock,
    )
}

@BindingContainer
object ViewerBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun networkMonitor(context: Context): NetworkMonitor = NetworkMonitor(context)

    @Provides
    @SingleIn(AppScope::class)
    fun wifiState(monitor: NetworkMonitor): WifiState = WifiState(monitor)

    @Provides
    @SingleIn(AppScope::class)
    fun statsService(
        index: IndexService,
        entries: EntriesService,
        rowStore: RowStorePort,
        settings: AppDeviceSettings,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): StatsService = DefaultStatsService(
        StatsServiceDeps(
            index = index::index,
            clock = SystemEntriesClock,
            readMetadata = entries::readMetadata,
            rowStore = rowStore,
            technical = { settings.settings.value.showTechnicalDetails },
            ioDispatcher = ioDispatcher,
        ),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun calendarThumbnails(thumbnails: GalleryThumbnails): CalendarThumbnails = CalendarThumbnails(thumbnails::load)

    @Provides
    @SingleIn(AppScope::class)
    fun viewerImages(context: Context, media: AccountMedia, thumbnails: GalleryThumbnails, @IoDispatcher ioDispatcher: CoroutineDispatcher): ViewerImages =
        CoilViewerImages(context, media, thumbnails, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun videoSource(media: AccountMedia, @IoDispatcher ioDispatcher: CoroutineDispatcher): AppVideoSource = AppVideoSource(media, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun viewerMedia(media: AccountMedia, @IoDispatcher ioDispatcher: CoroutineDispatcher): ViewerMedia = AppViewerMedia(media, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun entryOwners(rowStore: RowStorePort, @IoDispatcher ioDispatcher: CoroutineDispatcher): EntryOwners = EntryOwners(rowStore, ioDispatcher)

    @Provides
    fun calendarDeps(index: IndexService, settings: AppDeviceSettings, account: AppGalleryAccount, haptics: Haptics): CalendarDeps = CalendarDeps(
        index = index,
        clock = SystemCalendarClock,
        sync = UnwiredCalendarSync,
        accountMode = account.mode,
        settings = settings.settings,
        haptics = haptics::tick,
    )

    @Provides
    fun viewerDepsFactory(
        context: Context,
        index: IndexService,
        entries: EntriesService,
        media: AccountMedia,
        viewerMedia: ViewerMedia,
        videos: AppVideoSource,
        settings: AppDeviceSettings,
        wifi: WifiState,
        account: AppGalleryAccount,
        config: ConfigService,
        owners: EntryOwners,
        notices: Observable<GalleryNotice?>,
        filter: GalleryFilterStore,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ViewerDepsFactory = ViewerDepsFactory(context, index, entries, media, viewerMedia, videos, settings, wifi, account, config, owners, notices, filter, ioDispatcher)

    @Provides
    fun entryDetailsDeps(stats: StatsService, settings: AppDeviceSettings): EntryDetailsDeps = EntryDetailsDeps(stats = stats, settings = settings.settings)

    @Provides
    fun videoPlayerDeps(videos: AppVideoSource): VideoPlayerDeps = VideoPlayerDeps(source = videos)
}

package dev.kodakiko.app.di

import android.content.Context
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.app.AppConstants
import dev.kodakiko.app.edit.AppEditRecorder
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.app.gallery.AppDeviceSettings
import dev.kodakiko.app.gallery.AppGalleryAccount
import dev.kodakiko.app.gallery.CoilGalleryThumbnails
import dev.kodakiko.app.gallery.EntriesChangedBus
import dev.kodakiko.app.gallery.LifecycleSteps
import dev.kodakiko.app.gallery.SystemGalleryClock
import dev.kodakiko.app.gallery.UnwiredEntriesSync
import dev.kodakiko.app.gallery.UnwiredGallerySync
import dev.kodakiko.app.viewer.AppVideoSource
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.data.files.accountHash
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.platform.device.Haptics
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.DatabaseRowStore
import dev.kodakiko.service.account.DiskFileStore
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.entries.CaptureTray
import dev.kodakiko.service.entries.CaptureTrayDeps
import dev.kodakiko.service.entries.DefaultEntriesService
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.EntriesServiceDeps
import dev.kodakiko.service.entries.EntryFiles
import dev.kodakiko.service.entries.EntryKeyCache
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.entries.EntryKeysDeps
import dev.kodakiko.service.entries.FileStoreEntryFiles
import dev.kodakiko.service.entries.SystemEntriesClock
import dev.kodakiko.service.index.DefaultIndexService
import dev.kodakiko.service.index.EntryKeySource
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.index.IndexServiceDeps
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.service.sharing.CollectionNames
import dev.kodakiko.ui.core.notice.GalleryNotice
import dev.kodakiko.ui.feature.edit.EditFlushes
import dev.kodakiko.ui.feature.gallery.GalleryDeps
import dev.kodakiko.ui.feature.gallery.GalleryFilterStore
import dev.kodakiko.ui.feature.gallery.GalleryThumbnails
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@BindingContainer
object GalleryBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun entriesChanged(): EntriesChangedBus = EntriesChangedBus()

    @Provides
    @SingleIn(AppScope::class)
    fun entryKeys(sodium: Sodium, rowStore: DatabaseRowStore, accountState: Observable<AccountState>, @IoDispatcher ioDispatcher: CoroutineDispatcher): EntryKeys =
        EntryKeyCache(EntryKeysDeps(sodium = sodium, rowStore = rowStore, account = { (accountState.value as? AccountState.Unlocked)?.keys }, ioDispatcher = ioDispatcher))

    @Provides
    @SingleIn(AppScope::class)
    fun entryFiles(fileStore: DiskFileStore): EntryFiles = FileStoreEntryFiles { fileStore.require() }

    @Provides
    @SingleIn(AppScope::class)
    fun captureTray(files: EntryFiles, accountState: Observable<AccountState>, @IoDispatcher ioDispatcher: CoroutineDispatcher): CaptureTray =
        CaptureTray(CaptureTrayDeps(files = files, isUnlocked = { accountState.value is AccountState.Unlocked }, ioDispatcher = ioDispatcher))

    @Provides
    @SingleIn(AppScope::class)
    fun entriesService(
        sodium: Sodium,
        rowStore: DatabaseRowStore,
        files: EntryFiles,
        entryKeys: EntryKeys,
        captureTray: CaptureTray,
        config: ConfigService,
        bus: EntriesChangedBus,
        accountState: Observable<AccountState>,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): EntriesService = DefaultEntriesService(
        EntriesServiceDeps(
            sodium = sodium,
            rowStore = rowStore,
            files = files,
            entryKeys = entryKeys,
            captureTray = captureTray,
            config = config::current,
            sync = UnwiredEntriesSync,
            onEntriesChanged = bus::emit,
            clock = SystemEntriesClock,
            randomId = { encodeBytes(sodium.randomBytes(AppConstants.RANDOM_ID_BYTES)) },
            userId = { (accountState.value as? AccountState.Unlocked)?.userId },
            ioDispatcher = ioDispatcher,
        ),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun indexService(
        sodium: Sodium,
        rowStore: DatabaseRowStore,
        entryKeys: EntryKeys,
        bus: EntriesChangedBus,
        accountState: Observable<AccountState>,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): IndexService = DefaultIndexService(
        IndexServiceDeps(
            sodium = sodium,
            rowStore = rowStore,
            entryKeys = EntryKeySource { entryKeys.entryKey(it) },
            events = bus,
            now = System::currentTimeMillis,
            userId = { (accountState.value as? AccountState.Unlocked)?.userId },
            scope = scope,
            ioDispatcher = ioDispatcher,
        ),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun deviceSettings(rowStore: DatabaseRowStore, @ApplicationScope scope: CoroutineScope, @IoDispatcher ioDispatcher: CoroutineDispatcher): AppDeviceSettings =
        AppDeviceSettings(rowStore, scope, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun filterStore(): GalleryFilterStore = GalleryFilterStore()

    @Provides
    @SingleIn(AppScope::class)
    fun galleryNotices(): Observable<GalleryNotice?> = Observable(null)

    @Provides
    @SingleIn(AppScope::class)
    fun accountMedia(
        context: Context,
        rowStore: DatabaseRowStore,
        fileStore: DiskFileStore,
        sodium: Sodium,
        entryKeys: EntryKeys,
        settings: AppDeviceSettings,
        accountState: Observable<AccountState>,
        accountMode: Observable<AccountMode>,
    ): AccountMedia = AccountMedia(context, rowStore, fileStore, sodium, entryKeys, settings, { accountMode.value == AccountMode.LOCAL }) {
        accountState.value is AccountState.Unlocked
    }

    @Provides
    @SingleIn(AppScope::class)
    fun galleryThumbnails(context: Context, media: AccountMedia, @IoDispatcher ioDispatcher: CoroutineDispatcher): GalleryThumbnails =
        CoilGalleryThumbnails(context, media, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun tempFiles(context: Context, sodium: Sodium, @ActiveAccountKey activeAccountKey: Observable<String?>): TempFiles =
        TempFiles(context, sodium) { activeAccountKey.value?.let { accountHash(sodium, it) } }

    @Provides
    @SingleIn(AppScope::class)
    fun lifecycleSteps(
        index: IndexService,
        media: AccountMedia,
        tempFiles: TempFiles,
        filter: GalleryFilterStore,
        notices: Observable<GalleryNotice?>,
        settings: AppDeviceSettings,
        entryKeys: EntryKeys,
        videos: AppVideoSource,
        flushes: EditFlushes,
        recorder: AppEditRecorder,
        tray: CaptureTray,
        collectionNames: CollectionNames,
    ): LifecycleSteps = LifecycleSteps(index, media, tempFiles, filter, notices, settings, entryKeys, videos, flushes, recorder, tray, collectionNames)

    @Provides
    @SingleIn(AppScope::class)
    fun haptics(holder: ActivityHolder, settings: AppDeviceSettings): Haptics =
        Haptics({ holder.current?.window?.decorView }, { settings.settings.value.hapticFeedback })

    @Provides
    @SingleIn(AppScope::class)
    fun galleryAccount(auth: AuthService, lockControl: LockControl, accountState: Observable<AccountState>, @ApplicationScope scope: CoroutineScope): AppGalleryAccount =
        AppGalleryAccount(auth, lockControl, accountState, scope)

    @Provides
    fun galleryDeps(
        index: IndexService,
        entries: EntriesService,
        settings: AppDeviceSettings,
        filter: GalleryFilterStore,
        notices: Observable<GalleryNotice?>,
        account: AppGalleryAccount,
        config: ConfigService,
        haptics: Haptics,
    ): GalleryDeps = GalleryDeps(
        index = index,
        entries = entries,
        settings = settings,
        filter = filter,
        notices = notices,
        account = account,
        sync = UnwiredGallerySync,
        config = config,
        haptics = haptics::tick,
        clock = SystemGalleryClock,
        random = Math::random,
        dispatcher = Dispatchers.Main.immediate,
    )
}

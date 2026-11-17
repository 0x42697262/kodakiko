package dev.kodakiko.app.di

import android.content.Context
import androidx.lifecycle.ViewModel
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.app.AppConstants
import dev.kodakiko.app.edit.ActivityShareLauncher
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.app.gallery.AppGalleryAccount
import dev.kodakiko.app.gallery.EntriesChangedBus
import dev.kodakiko.app.log.AppLogConstants
import dev.kodakiko.app.log.uploadEvent
import dev.kodakiko.app.sharing.AppLinkSharing
import dev.kodakiko.app.sharing.InProcessUploader
import dev.kodakiko.app.sharing.RowStoreJournalRows
import dev.kodakiko.app.sharing.ServerSyncHttp
import dev.kodakiko.app.sync.AppSyncState
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.log.DebugLog
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.DiskFileStore
import dev.kodakiko.service.account.GlobalStorePort
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.EntryFiles
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.links.AndroidPhotoReencoder
import dev.kodakiko.service.links.DefaultLinkStripper
import dev.kodakiko.service.links.DefaultPublicLinksService
import dev.kodakiko.service.links.FileStoreLinkFiles
import dev.kodakiko.service.links.PublicLinksService
import dev.kodakiko.service.links.PublicLinksServiceDeps
import dev.kodakiko.service.shareout.ShareOutService
import dev.kodakiko.service.sharing.CollectionNames
import dev.kodakiko.service.sharing.CollectionNamesDeps
import dev.kodakiko.service.sharing.DefaultCollectionNames
import dev.kodakiko.service.sharing.DefaultSharingService
import dev.kodakiko.service.sharing.SharingAccount
import dev.kodakiko.service.sharing.SharingService
import dev.kodakiko.service.sharing.SharingServiceDeps
import dev.kodakiko.service.sync.SyncHttp
import dev.kodakiko.service.sync.SyncService
import dev.kodakiko.service.sync.SystemSyncClock
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.feature.gallery.GalleryThumbnails
import dev.kodakiko.ui.feature.links.LinkCreateViewModel
import dev.kodakiko.ui.feature.links.LinkCreateViewModelFactory
import dev.kodakiko.ui.feature.links.LinkThumbnails
import dev.kodakiko.ui.feature.links.LinksDeps
import dev.kodakiko.ui.feature.links.LinksListViewModel
import dev.kodakiko.ui.feature.sharing.CollectionViewModel
import dev.kodakiko.ui.feature.sharing.CollectionViewModelFactory
import dev.kodakiko.ui.feature.sharing.ShareSheetViewModel
import dev.kodakiko.ui.feature.sharing.ShareSheetViewModelFactory
import dev.kodakiko.ui.feature.sharing.ShareWithViewModel
import dev.kodakiko.ui.feature.sharing.ShareWithViewModelFactory
import dev.kodakiko.ui.feature.sharing.SharedJournalRows
import dev.kodakiko.ui.feature.sharing.SharedViewModel
import dev.kodakiko.ui.feature.sharing.SharingDeps
import dev.kodakiko.ui.feature.sharing.SharingThumbnails
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@BindingContainer
object SharingBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun syncHttp(http: HttpClient, accountMode: Observable<AccountMode>): SyncHttp = ServerSyncHttp(http, accountMode)

    @Provides
    @SingleIn(AppScope::class)
    fun collectionNames(sodium: Sodium, rowStore: RowStorePort, entryKeys: EntryKeys, @IoDispatcher ioDispatcher: CoroutineDispatcher): CollectionNames =
        DefaultCollectionNames(CollectionNamesDeps(sodium = sodium, rowStore = rowStore, entryKeys = entryKeys, ioDispatcher = ioDispatcher))

    @Provides
    @SingleIn(AppScope::class)
    fun sharingService(
        sodium: Sodium,
        http: SyncHttp,
        rowStore: RowStorePort,
        files: EntryFiles,
        entryKeys: EntryKeys,
        accountState: Observable<AccountState>,
        accountMode: Observable<AccountMode>,
        config: ConfigService,
        bus: EntriesChangedBus,
        sync: SyncService,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): SharingService = DefaultSharingService(
        SharingServiceDeps(
            sodium = sodium,
            http = http,
            rowStore = rowStore,
            files = files,
            entryKeys = entryKeys,
            account = { (accountState.value as? AccountState.Unlocked)?.let { SharingAccount(it.userId, it.keys) } },
            sharingEnabled = { accountMode.value == AccountMode.SERVER && config.current()?.sharingEnabled == true },
            randomId = { encodeBytes(sodium.randomBytes(AppConstants.RANDOM_ID_BYTES)) },
            clock = SystemSyncClock,
            afterChange = { entryIds ->
                bus.emit(entryIds)
                scope.launch { attempt { sync.syncNow() } }
            },
            ioDispatcher = ioDispatcher,
        ),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun journalRows(rowStore: RowStorePort, @IoDispatcher ioDispatcher: CoroutineDispatcher): SharedJournalRows = RowStoreJournalRows(rowStore, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun sharingThumbnails(thumbnails: GalleryThumbnails): SharingThumbnails = SharingThumbnails(thumbnails::load)

    @Provides
    @SingleIn(AppScope::class)
    fun linkThumbnails(thumbnails: GalleryThumbnails): LinkThumbnails = LinkThumbnails(thumbnails::load)

    @Provides
    fun sharingDeps(
        sharing: SharingService,
        names: CollectionNames,
        rows: SharedJournalRows,
        index: IndexService,
        shareOut: ShareOutService,
        config: ConfigService,
        account: AppGalleryAccount,
        sync: SyncService,
        syncState: AppSyncState,
    ): SharingDeps = SharingDeps(
        sharing = sharing,
        names = names,
        rows = rows,
        index = index,
        shareOut = shareOut,
        config = config,
        accountMode = account.mode,
        syncedAt = syncState.syncedAt,
        syncNow = { sync.syncNow() },
    )

    @Provides
    @SingleIn(AppScope::class)
    fun linkUploader(
        okHttpClient: OkHttpClient,
        fileStore: DiskFileStore,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        debugLog: DebugLog,
    ): InProcessUploader = InProcessUploader(okHttpClient, fileStore::require, scope, ioDispatcher).also { uploader ->
        uploader.subscribe { debugLog.uploadEvent(AppLogConstants.LINK_UPLOADS, it) }
    }

    @Provides
    @SingleIn(AppScope::class)
    fun publicLinksService(
        http: SyncHttp,
        sodium: Sodium,
        accountState: Observable<AccountState>,
        entries: EntriesService,
        entryKeys: EntryKeys,
        rowStore: RowStorePort,
        fileStore: DiskFileStore,
        uploader: InProcessUploader,
        globalStore: GlobalStorePort,
        media: AccountMedia,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): PublicLinksService = DefaultPublicLinksService(
        PublicLinksServiceDeps(
            http = http,
            sodium = sodium,
            account = { (accountState.value as? AccountState.Unlocked)?.keys },
            readMetadata = entries::readMetadata,
            entryKeys = entryKeys,
            rowStore = rowStore,
            files = FileStoreLinkFiles(fileStore::require),
            fetchObject = { objectId -> withContext(ioDispatcher) { media.fetchObject(objectId) } },
            stripper = DefaultLinkStripper(AndroidPhotoReencoder()),
            uploader = uploader,
            baseUrl = { withContext(ioDispatcher) { globalStore.serverUrl().orEmpty() } },
            randomId = { encodeBytes(sodium.randomBytes(AppConstants.RANDOM_ID_BYTES)) },
            clock = SystemSyncClock,
            ioDispatcher = ioDispatcher,
        ),
    )

    @Provides
    fun linksDeps(context: Context, links: PublicLinksService, entries: EntriesService, holder: ActivityHolder): LinksDeps = LinksDeps(
        links = links,
        readMetadata = entries::readMetadata,
        sharing = AppLinkSharing(context, ActivityShareLauncher(context, holder)),
        defaultTitle = context.getString(R.string.links_default_title),
        nowMs = System::currentTimeMillis,
    )

    @Provides
    @IntoMap
    @ViewModelKey(SharedViewModel::class)
    fun shared(deps: SharingDeps): ViewModel = SharedViewModel(deps)

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(CollectionViewModelFactory::class)
    fun collection(deps: SharingDeps): ManualViewModelAssistedFactory = CollectionViewModelFactory { collectionId -> CollectionViewModel(deps, collectionId) }

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(ShareWithViewModelFactory::class)
    fun shareWith(deps: SharingDeps): ManualViewModelAssistedFactory = ShareWithViewModelFactory { collectionId -> ShareWithViewModel(deps, collectionId) }

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(ShareSheetViewModelFactory::class)
    fun shareSheet(deps: SharingDeps): ManualViewModelAssistedFactory =
        ShareSheetViewModelFactory { entryIds, inKodakiko -> ShareSheetViewModel(deps, entryIds, inKodakiko) }

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(LinkCreateViewModelFactory::class)
    fun linkCreate(deps: LinksDeps): ManualViewModelAssistedFactory = LinkCreateViewModelFactory { entryIds -> LinkCreateViewModel(deps, entryIds) }

    @Provides
    @IntoMap
    @ViewModelKey(LinksListViewModel::class)
    fun linksList(deps: LinksDeps): ViewModel = LinksListViewModel(deps)
}

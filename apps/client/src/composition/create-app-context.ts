import { router } from 'expo-router';
import { startKdfParams, type Sodium } from '@kodakiko/core';
import { systemClock, type Clock } from '../adapters/clock';
import { deviceLabel } from '../adapters/device-label/device-label';
import { createGlobalStore, type GlobalStore } from '../adapters/global-store/global-store';
import { createFileStore } from '../adapters/filestore/filestore';
import { createHttpClient, type HttpClient } from '../adapters/http/http';
import { consoleLogger } from '../adapters/logger';
import { createRandomId } from '../adapters/ids';
import { createKeyVault } from '../adapters/keyvault/keyvault';
import { createLifecycle, type Lifecycle } from '../adapters/lifecycle/lifecycle';
import { createLocationProvider, type LocationProvider } from '../adapters/location/location';
import { createMediaToolkit } from '../adapters/media/media-toolkit';
import { createTempFiles, type TempFiles } from '../adapters/temp-files/temp-files';
import { createPlaintextSink } from '../adapters/plaintext-sink/plaintext-sink';
import { createLocalOriginalSource } from '../services/originals/original-source';
import { createDownloadingOriginalSource, type OriginalsService } from '../services/originals/originals-service';
import { createDownloader, type Downloader } from '../services/downloads/downloader';
import { createWifiUploadPolicy } from '../services/downloads/upload-policy';
import { createCachingThumbnailSource, type ThumbnailCacheService } from '../services/thumbnails/thumbnail-cache';
import { createNetworkInfo } from '../adapters/network/network';
import type { VideoLauncher } from '../services/video/video-launcher';
import type { VideoSource } from '../services/video/video-source';
import { createVideoSource } from '../adapters/video-source/video-source';
import type { GalleryNotice } from '../features/gallery/gallery-notice-model';
import { createRowStore } from '../adapters/rowstore/rowstore';
import { createServerUrlStore, type ServerUrlStore } from '../adapters/server-url/server-url';
import { createSessionStore, type SessionStore } from '../adapters/session/session-store';
import { loadSodium } from '../adapters/sodium/sodium';
import { requestNotificationPermission } from '../adapters/uploader/notification-permission';
import { createUploader, type Uploader } from '../adapters/uploader/uploader';
import type { FileStore } from '../adapters/filestore/filestore';
import type { KeyVault } from '../adapters/keyvault/keyvault';
import type { RowStore } from '../adapters/rowstore/rowstore';
import { DEVICE_SETTINGS_DEFAULTS } from '../constants/device-settings-defaults';
import { ORPHAN_SWEEP_AGE_MS } from '../constants/media-constants';
import { createCaptureTrayStore, type CaptureTrayStore } from '../features/capture/capture-tray-store';
import { MEDIA_LOG_EVENTS } from '../keys/media-keys';
import { APP_ROUTES, playRoute } from '../keys/routes';
import { STORAGE_KEYS } from '../keys/storage-keys';
import { createAuthService, type AuthService } from '../services/auth-service';
import { createConfigService, type ConfigService } from '../services/config/config-service';
import type { EntryKeys } from '../services/keys/entry-keys';
import { createEntriesService, type EntriesService } from '../services/entries/entries-service';
import { createImportService, type ImportService } from '../services/media/import-service';
import { createBoundDeviceSettings } from '../services/settings/bound-device-settings';
import type { TagSuggestionSource } from '../services/tags/tag-suggestions';
import {
  createIndexService,
  createIndexTagSuggestionSource,
  type IndexBuildReport,
  type IndexService,
} from '../services/index/index-service';
import {
  createLocalThumbnailSource,
} from '../services/thumbnails/thumbnail-source';
import {
  createGalleryFilterStore,
  type GalleryFilterStore,
} from '../features/gallery/gallery-store';
import { GALLERY_LOG_EVENTS } from '../keys/gallery-ids';
import type { DeviceSettings } from '../services/settings/device-settings';
import { createSyncStack } from '../services/sync/create-sync-stack';
import type { NotificationPrompt } from '../services/sync/notification-prompt';
import type { SyncService } from '../services/sync/sync-service';
import type { UploadCoordinator } from '../services/sync/uploads';
import { EN } from '../text/en';
import { createActivitySource, pageVisibility } from '../adapters/activity/activity';
import { AUTO_LOCK_MODE } from '../constants/auto-lock-mode';
import { createAutoLock, type ActivitySource, type AutoLock } from '../services/lock/auto-lock';
import { createLockService } from '../services/lock/lock-service';
import { createCollectionNames, type CollectionNames } from '../services/sharing/collection-names';
import { createSharingService, type SharingService } from '../services/sharing/sharing-service';
import { createAccountService, type AccountService } from '../services/account/account-service';
import { createAdminClient, type AdminClient } from '../services/admin/admin-service';
import { createPublicLinksService, type PublicLinksService } from '../services/links/public-links-service';
import { createLinkStripper } from '../services/links/link-stripper';
import { createPhotoReencoder } from '../adapters/photo-reencoder/photo-reencoder';
import { createEventBus, type AppEventBus, type AppEvents } from './events';
import {
  createAccountState,
  createObservable,
  type AccountStateStore,
  type Observable,
} from './account-state';

export type Services = {
  readonly auth: AuthService;
  readonly config: ConfigService;
  readonly sync: SyncService;
  readonly uploads: UploadCoordinator;
  readonly entryKeys: EntryKeys;
  readonly notificationPrompt: NotificationPrompt;
  readonly mediaImport: ImportService;
  readonly captureTray: CaptureTrayStore;
  readonly deviceSettings: DeviceSettings;
  readonly location: LocationProvider;
  readonly entries: EntriesService;
  readonly tagSuggestions: TagSuggestionSource;
  readonly savedNotice: Observable<number | null>;
  readonly index: IndexService;
  readonly lastIndexBuild: Observable<IndexBuildReport | null>;
  readonly thumbnails: ThumbnailCacheService;
  readonly galleryFilter: GalleryFilterStore;
  readonly originals: OriginalsService;
  readonly downloader: Downloader;
  readonly galleryNotice: Observable<GalleryNotice | null>;
  readonly videoLauncher: VideoLauncher;
  readonly lock: LockControl;
  readonly sharing: SharingService;
  readonly collectionNames: CollectionNames;
  readonly links: PublicLinksService;
  readonly account: AccountService;
  readonly admin: AdminClient;
  readonly videoSource: VideoSource;
};

export type LockControl = {
  readonly lockNow: () => Promise<void>;
  readonly setKeepUnlocked: (enabled: boolean) => Promise<void>;
  readonly keepUnlocked: () => boolean;
  readonly autoLock: AutoLock;
  readonly activity: ActivitySource;
  readonly returnTo: Observable<string | null>;
  readonly lockedInSession: () => boolean;
};

export type AppContext = {
  readonly sodium: Sodium;
  readonly serverUrl: ServerUrlStore;
  readonly sessionStore: SessionStore;
  readonly keyVault: KeyVault;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly lifecycle: Lifecycle;
  readonly http: HttpClient;
  readonly clock: Clock;
  readonly randomId: () => string;
  readonly accountState: AccountStateStore;
  readonly activeAccountKey: Observable<string | null>;
  readonly inviteToken: Observable<string | null>;
  readonly globalStore: GlobalStore;
  readonly events: AppEventBus;
  readonly uploader: Uploader;
  readonly tempFiles: TempFiles;
  readonly services: Services;
};

export async function createAppContext(): Promise<AppContext> {
  const sodium = await loadSodium();
  const serverUrl = createServerUrlStore();
  const sessionStore = createSessionStore({ sodium });
  const keyVault = createKeyVault({ sodium });
  const rowStore = createRowStore({ sodium });
  const fileStore = createFileStore({
    sodium,
    onPersistResult: (granted) => {
      rowStore.kvSetJson(STORAGE_KEYS.deviceStoragePersisted, granted).catch(() => undefined);
    },
  });
  const lifecycle = createLifecycle();
  const accountState = createAccountState({
    kind: (await serverUrl.current()) === null ? 'noServer' : 'signedOut',
  });
  const activeAccountKey = createObservable<string | null>(null);
  const http = createHttpClient({
    baseUrl: async () => {
      const current = await serverUrl.current();
      if (current === null) throw new Error(EN['http.noServer']);
      return current;
    },
    sessionToken: async () => {
      const accountKey = activeAccountKey.get();
      return accountKey === null ? null : sessionStore.load(accountKey);
    },
    onSessionInvalid: () => sessionInvalid(),
  });
  const globalStore = createGlobalStore();
  const inviteToken = createObservable<string | null>(null);
  const config = createConfigService({ http, globalStore, lifecycle });
  const randomId = createRandomId(sodium);
  const auth = createAuthService({
    sodium,
    http,
    serverUrl,
    sessionStore,
    keyVault,
    rowStore,
    fileStore,
    globalStore,
    lifecycle,
    clock: systemClock,
    randomId,
    accountState,
    activeAccountKey,
    config,
    inviteToken,
    deviceLabel,
    kdfStartParams: startKdfParams,
    showSecuring: () => router.push(APP_ROUTES.securing),
    dismissSecuring: () => router.back(),
    beforeSignOut: async () => {
      await lockService.lock('signOut');
      await uploader.cancelAll();
    },
  });
  const events = createEventBus<AppEvents>();
  const uploader = createUploader({ fileStore });
  const stack = createSyncStack({
    sodium,
    http,
    rowStore,
    fileStore,
    uploader,
    events,
    clock: systemClock,
    lifecycle,
    accountState,
    onSessionInvalid: () => sessionInvalid(),
    log: consoleLogger,
    requestNotificationPermission,
    uploadPolicy: { allowed: (sizeBytes) => uploadPolicy.allowed(sizeBytes) },
    thumbnailCache: { afterUpload: (objectId) => thumbnails.afterUpload(objectId) },
  });
  const tempFiles = createTempFiles({ sodium, activeAccountKey: () => activeAccountKey.get() });
  const boundSettings = createBoundDeviceSettings({
    rowStore,
    defaults: DEVICE_SETTINGS_DEFAULTS,
    onInvalidFields: (fields) => {
      if (__DEV__) {
        consoleLogger.warn(MEDIA_LOG_EVENTS.invalidDeviceSettings, { fields: fields.join(',') });
      }
    },
  });
  const captureTray = createCaptureTrayStore({
    fileStore,
    isUnlocked: () => accountState.get().kind === 'unlocked',
  });
  const mediaImport = createImportService({
    sodium,
    fileStore,
    toolkit: createMediaToolkit(),
    clock: systemClock,
    config,
    deviceSettings: boundSettings.settings,
    randomId,
  });
  const entries = createEntriesService({
    sodium,
    rowStore,
    fileStore,
    entryKeys: stack.entryKeys,
    captureTray,
    config,
    sync: stack.sync,
    events,
    clock: systemClock,
    randomId,
    account: () => {
      const state = accountState.get();
      return state.kind === 'unlocked' ? { userId: state.userId } : null;
    },
  });
  const lastIndexBuild = createObservable<IndexBuildReport | null>(null);
  const index = createIndexService({
    sodium,
    rowStore,
    entryKeys: stack.entryKeys,
    events,
    clock: systemClock,
    account: () => {
      const state = accountState.get();
      return state.kind === 'unlocked' ? { userId: state.userId } : null;
    },
    onBuilt: (report) => {
      lastIndexBuild.set(report);
      if (__DEV__) console.info(GALLERY_LOG_EVENTS.indexBuilt, JSON.stringify(report));
    },
  });
  const network = createNetworkInfo();
  const uploadPolicy = createWifiUploadPolicy({
    network,
    settings: () => boundSettings.settings.get(),
    initialWifi: true,
  });
  uploadPolicy.onChange(() => {
    if (accountState.get().kind === 'unlocked') stack.sync.notifyLocalWrite();
    originals.downloadPinned().catch(() => undefined);
  });
  const downloader = createDownloader({
    http,
    rowStore,
    fileStore,
    clock: systemClock,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    isOffline: () => stack.sync.status().offline,
  });
  const thumbnails = createCachingThumbnailSource({
    local: createLocalThumbnailSource({ fileStore, rowStore, entryKeys: stack.entryKeys, sodium }),
    downloader,
    rowStore,
    fileStore,
    entryKeys: stack.entryKeys,
    sodium,
    settings: () => boundSettings.settings.get(),
  });
  const galleryFilter = createGalleryFilterStore();
  const galleryNotice = createObservable<GalleryNotice | null>(null);
  const originals = createDownloadingOriginalSource({
    local: createLocalOriginalSource({
      rowStore,
      fileStore,
      entryKeys: stack.entryKeys,
      sodium,
      clock: systemClock,
      readMetadata: entries.readMetadata,
      sink: createPlaintextSink({ tempFiles }),
    }),
    downloader,
    rowStore,
    fileStore,
    settings: () => boundSettings.settings.get(),
    isWifi: () => network.isWifi(),
    now: systemClock.now,
  });
  const videoSource = createVideoSource({
    rowStore,
    fileStore,
    entryKeys: stack.entryKeys,
    sodium,
    http,
    readMetadata: entries.readMetadata,
    downloader,
    tempFiles,
    randomId,
  });
  const videoLauncher: VideoLauncher = {
    canPlay: () => true,
    open: (entryId, mediaId) => router.push(playRoute(entryId, mediaId)),
  };
  stack.sync.onPassCompleted(() => {
    if (accountState.get().kind !== 'unlocked') return;
    downloader.resume();
    thumbnails.prefetchAll().catch(() => undefined);
  });
  await tempFiles.wipe().catch(() => undefined);
  const sharing = createSharingService({
    sodium,
    http,
    rowStore,
    fileStore,
    entryKeys: stack.entryKeys,
    account: () => {
      const state = accountState.get();
      return state.kind === 'unlocked' ? { userId: state.userId, keys: state.keys } : null;
    },
    sharingEnabled: () => config.get()?.sharingEnabled === true,
    randomId,
    clock: systemClock,
    afterChange: (entryIds) => {
      if (entryIds.length > 0) events.emit('entriesChanged', [...entryIds]);
      stack.sync.syncNow().catch(() => undefined);
    },
  });
  const collectionNames = createCollectionNames({ sodium, rowStore, entryKeys: stack.entryKeys });
  const links = createPublicLinksService({
    http,
    sodium,
    account: () => {
      const state = accountState.get();
      return state.kind === 'unlocked' ? state.keys : null;
    },
    readMetadata: entries.readMetadata,
    entryKeys: stack.entryKeys,
    rowStore,
    fileStore,
    fetchObject: (objectId) => downloader.fetchObject(objectId, 'opened'),
    stripper: createLinkStripper({ reencodePhoto: createPhotoReencoder({ tempFiles }) }),
    uploader,
    baseUrl: async () => (await serverUrl.current()) ?? '',
    randomId,
    clock: systemClock,
  });
  const account = createAccountService({ http, rowStore });
  const admin = createAdminClient({ http });
  lifecycle.subscribeActive((active) => {
    if (active && accountState.get().kind === 'unlocked') account.refreshMe().catch(() => undefined);
  });
  const returnTo = createObservable<string | null>(null);
  const lockService = createLockService({
    accountState,
    log: (event, data) => consoleLogger.warn(event, { step: String(data.step) }),
    lockSteps: [
      { name: 'stopSync', run: () => stack.sync.stop() },
      { name: 'cancelDownloads', run: () => downloader.abortAll() },
      { name: 'releaseVideo', run: () => videoSource.releaseAll() },
      { name: 'releaseOriginals', run: () => originals.clearAll() },
      { name: 'clearThumbnails', run: () => thumbnails.clear() },
      { name: 'clearTray', run: () => captureTray.clear(true) },
      { name: 'wipeTemp', run: () => tempFiles.wipe() },
      { name: 'clearIndex', run: () => index.clear() },
      {
        name: 'clearUiState',
        run: () => {
          galleryFilter.reset();
          galleryNotice.set(null);
          boundSettings.unload();
        },
      },
      { name: 'clearCollectionNames', run: () => collectionNames.clear() },
      { name: 'clearAccount', run: () => account.clear() },
      { name: 'wipeKeys', run: () => stack.entryKeys.clear() },
    ],
    unlockSteps: [
      { name: 'loadSettings', run: () => boundSettings.load() },
      { name: 'startSync', run: () => stack.sync.start() },
      { name: 'buildIndex', run: () => index.build() },
      { name: 'sweepOrphans', run: () => sweepOrphans(rowStore, fileStore) },
      {
        name: 'enforceCache',
        run: async () => {
          await originals.enforceCap();
          originals.downloadPinned().catch(() => undefined);
        },
      },
      { name: 'refreshMe', run: async () => void (await account.refreshMe()) },
    ],
  });
  function sessionInvalid(): void {
    lockService
      .lock('sessionInvalid')
      .finally(() => {
        accountState.set({ kind: 'signedOut' });
        uploader.cancelAll().catch(() => undefined);
      })
      .catch(() => undefined);
  }
  let keepUnlockedCached = false;
  const activity = createActivitySource(systemClock.now);
  const autoLock = createAutoLock({
    mode: AUTO_LOCK_MODE,
    lifecycle,
    clock: systemClock,
    settings: boundSettings.settings,
    lockService,
    accountState,
    keepUnlocked: () => keepUnlockedCached,
    activity,
    onVisible: pageVisibility.onVisible,
  });
  async function refreshKeepUnlocked(): Promise<void> {
    keepUnlockedCached = await auth.keepUnlocked().catch(() => false);
  }
  async function setKeepUnlocked(enabled: boolean): Promise<void> {
    await auth.setKeepUnlocked(enabled);
    await refreshKeepUnlocked();
  }
  lockService.bind();
  let autoLockRunning = false;
  function syncAutoLock(): void {
    const unlocked = accountState.get().kind === 'unlocked';
    if (unlocked && !autoLockRunning) {
      autoLock.start();
      refreshKeepUnlocked().catch(() => undefined);
    }
    if (!unlocked && autoLockRunning) autoLock.stop();
    autoLockRunning = unlocked;
  }
  accountState.subscribe(syncAutoLock);
  syncAutoLock();
  const lockControl: LockControl = {
    lockNow: async () => {
      await setKeepUnlocked(false);
      await lockService.lock('manual');
    },
    setKeepUnlocked,
    keepUnlocked: () => keepUnlockedCached,
    autoLock,
    activity,
    returnTo,
    lockedInSession: () => lockService.lockCount() > 0,
  };
  await auth.restore();
  return {
    sodium,
    serverUrl,
    sessionStore,
    keyVault,
    rowStore,
    fileStore,
    lifecycle,
    http,
    clock: systemClock,
    randomId,
    accountState,
    activeAccountKey,
    inviteToken,
    globalStore,
    events,
    uploader,
    tempFiles,
    services: {
      auth,
      config,
      sync: stack.sync,
      uploads: stack.uploads,
      entryKeys: stack.entryKeys,
      notificationPrompt: stack.notificationPrompt,
      mediaImport,
      captureTray,
      deviceSettings: boundSettings.settings,
      location: createLocationProvider(),
      entries,
      tagSuggestions: createIndexTagSuggestionSource(index),
      originals,
      downloader,
      galleryNotice,
      videoLauncher,
      lock: lockControl,
      sharing,
      collectionNames,
      links,
      account,
      admin,
      videoSource,
      savedNotice: createObservable<number | null>(null),
      index,
      lastIndexBuild,
      thumbnails,
      galleryFilter,
    },
  };
}

async function sweepOrphans(rowStore: RowStore, fileStore: FileStore): Promise<void> {
  const known = await rowStore.transaction(async (tx) => ({
    objects: (await tx.objects.all()).map((row) => row.id),
    media: (await tx.media.all()).map((row) => row.id),
  }));
  await fileStore.sweepOrphans(new Set(known.objects), ORPHAN_SWEEP_AGE_MS, new Set(known.media));
}

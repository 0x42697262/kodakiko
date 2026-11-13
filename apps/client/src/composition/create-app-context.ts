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
import { createLocalOriginalSource, type OriginalSource } from '../services/originals/original-source';
import { placeholderVideoLauncher, type VideoLauncher } from '../services/video/video-launcher';
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
import { APP_ROUTES } from '../keys/routes';
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
  type ThumbnailSource,
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
import { bindAccountLifecycle } from './account-lifecycle';
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
  readonly thumbnails: ThumbnailSource;
  readonly galleryFilter: GalleryFilterStore;
  readonly originals: OriginalSource;
  readonly galleryNotice: Observable<GalleryNotice | null>;
  readonly videoLauncher: VideoLauncher;
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
    onSessionInvalid: () => accountState.set({ kind: 'signedOut' }),
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
    onSessionInvalid: () => accountState.set({ kind: 'signedOut' }),
    log: consoleLogger,
    requestNotificationPermission,
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
  const thumbnails = createLocalThumbnailSource({
    fileStore,
    rowStore,
    entryKeys: stack.entryKeys,
    sodium,
  });
  const galleryFilter = createGalleryFilterStore();
  const galleryNotice = createObservable<GalleryNotice | null>(null);
  const originals = createLocalOriginalSource({
    rowStore,
    fileStore,
    entryKeys: stack.entryKeys,
    sodium,
    clock: systemClock,
    readMetadata: entries.readMetadata,
    sink: createPlaintextSink({ tempFiles }),
  });
  await tempFiles.wipe().catch(() => undefined);
  bindAccountLifecycle(accountState, {
    onUnlocked: () => {
      stack.sync.start();
      index.build().catch(() => undefined);
      boundSettings
        .load()
        .catch(() => undefined);
      sweepOrphans(rowStore, fileStore).catch(() => undefined);
    },
    onLeftUnlocked: () => {
      stack.sync.stop();
      index.clear();
      thumbnails.clear();
      originals.clearAll().catch(() => undefined);
      galleryFilter.reset();
      galleryNotice.set(null);
      stack.entryKeys.clear();
      boundSettings.unload();
      captureTray.clear(true).catch(() => undefined);
      tempFiles.wipe().catch(() => undefined);
      const kind = accountState.get().kind;
      if (kind === 'signedOut' || kind === 'noServer') uploader.cancelAll().catch(() => undefined);
    },
  });
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
      galleryNotice,
      videoLauncher: placeholderVideoLauncher,
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

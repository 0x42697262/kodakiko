import type { Sodium } from '@kodakiko/core';
import type { Clock } from '../../adapters/clock';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { HttpClient } from '../../adapters/http/http';
import type { Lifecycle } from '../../adapters/lifecycle/lifecycle';
import type { Logger } from '../../adapters/logger';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import type { Uploader } from '../../adapters/uploader/uploader';
import type { NotificationPermissionRequest } from '../../adapters/uploader/notification-permission';
import type { AccountStateStore } from '../../composition/account-state';
import type { AppEventBus } from '../../composition/events';
import { createConflictResolver } from './conflict';
import { createEntryKeys, type EntryKeys } from '../keys/entry-keys';
import { alwaysAllowedUploadPolicy, noopThumbnailCache } from './noop-dependencies';
import { createNotificationPrompt, type NotificationPrompt } from './notification-prompt';
import { createPull } from './pull';
import { createSyncService, type SyncService } from './sync-service';
import { createUploadCoordinator, type ThumbnailCache, type UploadCoordinator, type UploadPolicy } from './uploads';

export type SyncStackDeps = {
  readonly sodium: Sodium;
  readonly http: HttpClient;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly uploader: Uploader;
  readonly events: AppEventBus;
  readonly clock: Clock;
  readonly lifecycle: Lifecycle;
  readonly accountState: AccountStateStore;
  readonly onSessionInvalid: () => void;
  readonly log: Logger;
  readonly requestNotificationPermission: NotificationPermissionRequest;
  readonly random?: () => number;
  readonly uploadPolicy?: UploadPolicy;
  readonly thumbnailCache?: ThumbnailCache;
};

export type SyncStack = {
  readonly sync: SyncService;
  readonly uploads: UploadCoordinator;
  readonly entryKeys: EntryKeys;
  readonly notificationPrompt: NotificationPrompt;
};

export function createSyncStack(deps: SyncStackDeps): SyncStack {
  const entryKeys = createEntryKeys({
    sodium: deps.sodium,
    rowStore: deps.rowStore,
    account: () => {
      const state = deps.accountState.get();
      return state.kind === 'unlocked' ? state.keys : null;
    },
  });
  const notificationPrompt = createNotificationPrompt({
    rowStore: deps.rowStore,
    request: deps.requestNotificationPermission,
  });
  const uploads = createUploadCoordinator({
    http: deps.http,
    rowStore: deps.rowStore,
    uploader: deps.uploader,
    events: deps.events,
    uploadPolicy: deps.uploadPolicy ?? alwaysAllowedUploadPolicy,
    thumbnailCache: deps.thumbnailCache ?? noopThumbnailCache,
    notificationPrompt,
    log: deps.log,
  });
  const conflict = createConflictResolver({
    sodium: deps.sodium,
    rowStore: deps.rowStore,
    entryKeys,
    clock: deps.clock,
  });
  const pull = createPull({
    http: deps.http,
    rowStore: deps.rowStore,
    fileStore: deps.fileStore,
    events: deps.events,
    log: deps.log,
  });
  const sync = createSyncService({
    http: deps.http,
    rowStore: deps.rowStore,
    fileStore: deps.fileStore,
    uploader: deps.uploader,
    uploads,
    pull,
    conflict,
    events: deps.events,
    clock: deps.clock,
    random: deps.random ?? Math.random,
    lifecycle: deps.lifecycle,
    accountState: deps.accountState,
    onSessionInvalid: deps.onSessionInvalid,
    log: deps.log,
  });
  return { sync, uploads, entryKeys, notificationPrompt };
}

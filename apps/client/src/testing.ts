import { startKdfParams, type Sodium } from '@kodakiko/core';
import { systemClock, type Clock } from './adapters/clock';
import { createHttpClient } from './adapters/http/http';
import { createRandomId } from './adapters/ids';
import { createKeyVault } from './adapters/keyvault/keyvault.web';
import type { FileStore } from './adapters/filestore/filestore';
import type { RowStore } from './adapters/rowstore/rowstore';
import type { Uploader } from './adapters/uploader/uploader';
import { createMemoryFileStore } from './adapters/memory/memory-file-store';
import { createMemoryGlobalStore } from './adapters/memory/memory-global-store';
import { createMemoryRowStore } from './adapters/memory/memory-row-store';
import { createMemorySessionStore } from './adapters/memory/memory-session-store';
import { silentLogger, type Logger } from './adapters/logger';
import type { Lifecycle } from './adapters/lifecycle/lifecycle';
import { createMemoryMediaToolkit } from './adapters/memory/memory-media-toolkit';
import { createWebUploader } from './adapters/uploader/uploader.web';
import { bindAccountLifecycle } from './composition/account-lifecycle';
import { createAccountState, createObservable } from './composition/account-state';
import { createEventBus, type AppEventBus, type AppEvents } from './composition/events';
import { SEED_TRAY_LIMIT } from './constants/entry-constants';
import { TEST_DEVICE_LABEL, TEST_DEVICE_SETTINGS } from './constants/testing-constants';
import { createCaptureTrayStore, type CaptureTrayStore } from './features/capture/capture-tray-store';
import {
  createAuthService,
  type AuthService,
  type AuthServiceContext,
} from './services/auth-service';
import { createEntriesService, type EntriesService } from './services/entries/entries-service';
import { createImportService, type ImportService } from './services/media/import-service';
import { createSyncStack, type SyncStack } from './services/sync/create-sync-stack';

export type TestAuthContextInput = {
  readonly baseUrl: string;
  readonly fetcher: typeof fetch;
  readonly sodium: Sodium;
};

export type TestAuthContext = AuthServiceContext & { readonly auth: AuthService };

export function createTestAuthContext(input: TestAuthContextInput): TestAuthContext {
  const sessionStore = createMemorySessionStore();
  const accountState = createAccountState({ kind: 'signedOut' });
  const activeAccountKey = createObservable<string | null>(null);
  const http = createHttpClient({
    baseUrl: async () => input.baseUrl,
    sessionToken: async () => {
      const accountKey = activeAccountKey.get();
      return accountKey === null ? null : sessionStore.load(accountKey);
    },
    onSessionInvalid: () => accountState.set({ kind: 'signedOut' }),
    fetcher: input.fetcher,
  });
  const context: AuthServiceContext = {
    sodium: input.sodium,
    http,
    serverUrl: {
      current: async () => input.baseUrl,
      choose: async () => ({ ok: false, reason: 'invalid' }),
    },
    sessionStore,
    keyVault: createKeyVault({ sodium: input.sodium }),
    rowStore: createMemoryRowStore(),
    fileStore: createMemoryFileStore(),
    globalStore: createMemoryGlobalStore(),
    lifecycle: {
      afterInteractions: async () => undefined,
      subscribeActive: () => () => undefined,
      subscribeOnline: () => () => undefined,
    },
    clock: systemClock,
    randomId: createRandomId(input.sodium),
    accountState,
    activeAccountKey,
    inviteToken: createObservable<string | null>(null),
    config: {
      get: () => null,
      refresh: async () => null,
      accept: () => undefined,
      subscribe: () => () => undefined,
    },
    deviceLabel: () => TEST_DEVICE_LABEL,
    kdfStartParams: startKdfParams,
    showSecuring: () => undefined,
    dismissSecuring: () => undefined,
  };
  return { ...context, auth: createAuthService(context) };
}

export type TestClientContextInput = {
  readonly baseUrl: string;
  readonly fetcher: typeof fetch;
  readonly sodium: Sodium;
  readonly clock: Clock;
  readonly log?: Logger;
  readonly random?: () => number;
};

export type TestClientContext = TestAuthContext &
  SyncStack & {
    readonly events: AppEventBus;
    readonly uploader: Uploader;
    readonly rowStore: RowStore;
    readonly fileStore: FileStore;
    readonly entries: EntriesService;
    readonly captureTray: CaptureTrayStore;
    readonly mediaImport: ImportService;
  };

export type TrayFile = { readonly bytes: Uint8Array; readonly fileName: string };

const FAKE_LIFECYCLE: Lifecycle = {
  afterInteractions: async () => undefined,
  subscribeActive: () => () => undefined,
  subscribeOnline: () => () => undefined,
};

export async function createTestClientContext(
  input: TestClientContextInput,
): Promise<TestClientContext> {
  const auth = createTestAuthContext({
    baseUrl: input.baseUrl,
    fetcher: input.fetcher,
    sodium: input.sodium,
  });
  const events = createEventBus<AppEvents>();
  const uploader = createWebUploader({ fileStore: auth.fileStore, fetcher: input.fetcher });
  const stack = createSyncStack({
    sodium: input.sodium,
    http: auth.http,
    rowStore: auth.rowStore,
    fileStore: auth.fileStore,
    uploader,
    events,
    clock: input.clock,
    lifecycle: FAKE_LIFECYCLE,
    accountState: auth.accountState,
    onSessionInvalid: () => auth.accountState.set({ kind: 'signedOut' }),
    log: input.log ?? silentLogger,
    requestNotificationPermission: null,
    ...(input.random === undefined ? {} : { random: input.random }),
  });
  const captureTray = createCaptureTrayStore({
    fileStore: auth.fileStore,
    isUnlocked: () => auth.accountState.get().kind === 'unlocked',
  });
  const mediaImport = createImportService({
    sodium: input.sodium,
    fileStore: auth.fileStore,
    toolkit: createMemoryMediaToolkit(),
    clock: input.clock,
    config: auth.config,
    deviceSettings: {
      get: () => TEST_DEVICE_SETTINGS,
      loaded: () => true,
      update: async () => undefined,
      subscribe: () => () => undefined,
    },
    randomId: auth.randomId,
  });
  const entries = createEntriesService({
    sodium: input.sodium,
    rowStore: auth.rowStore,
    fileStore: auth.fileStore,
    entryKeys: stack.entryKeys,
    captureTray,
    config: auth.config,
    sync: stack.sync,
    events,
    clock: input.clock,
    randomId: auth.randomId,
    account: () => {
      const state = auth.accountState.get();
      return state.kind === 'unlocked' ? { userId: state.userId } : null;
    },
  });
  bindAccountLifecycle(auth.accountState, {
    onUnlocked: () => undefined,
    onLeftUnlocked: () => {
      stack.sync.stop();
      stack.entryKeys.clear();
      captureTray.clear(true).catch(() => undefined);
    },
  });
  return { ...auth, ...stack, events, uploader, entries, captureTray, mediaImport };
}

export async function importIntoTray(
  context: Pick<TestClientContext, 'mediaImport' | 'captureTray'>,
  files: readonly TrayFile[],
): Promise<readonly string[]> {
  const mediaIds: string[] = [];
  for (const file of files) {
    const outcome = await context.mediaImport.importOne(
      {
        origin: 'picker',
        handle: { blob: new Blob([file.bytes.slice()]) },
        fileName: file.fileName,
        pickerExif: null,
      },
      { used: context.captureTray.items.length, limit: SEED_TRAY_LIMIT },
    );
    if (!outcome.ok) throw new Error(`${file.fileName}: ${outcome.reason}`);
    context.captureTray.add(outcome.media);
    mediaIds.push(outcome.media.mediaId);
  }
  return mediaIds;
}

export { createWrappersSodium } from './adapters/sodium/sodium-wrappers';

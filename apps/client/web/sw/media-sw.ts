import sodiumLib from 'libsodium-wrappers-sumo';
import type { Sodium } from '@kodakiko/core';
import { createWrappersSodium } from '../../src/adapters/sodium/sodium-wrappers';
import { SW_KEY_REQUEST_TIMEOUT_MS } from './constants';
import {
  createMediaWorkerState,
  forgetAllPlaybacks,
  forgetPlayback,
  handleMediaRequest,
  playbackIdOf,
} from './media-handler';
import type { PageToWorkerMessage, WorkerToPageMessage } from './messages';

export { handleMediaRequest } from './media-handler';

type MessageTarget = { readonly postMessage: (message: WorkerToPageMessage) => void };
type WindowClient = MessageTarget & { readonly id: string };
type ExtendableEvent = { readonly waitUntil: (work: Promise<unknown>) => void };
type FetchEvent = ExtendableEvent & {
  readonly request: Request;
  readonly clientId: string;
  readonly respondWith: (response: Promise<Response>) => void;
};
type MessageEventLike = { readonly data: PageToWorkerMessage; readonly source: MessageTarget | null };
type WorkerScope = {
  readonly skipWaiting: () => Promise<void>;
  readonly clients: {
    readonly claim: () => Promise<void>;
    readonly get: (id: string) => Promise<WindowClient | undefined>;
    readonly matchAll: (options: { readonly type: 'window' }) => Promise<readonly WindowClient[]>;
  };
  readonly addEventListener: {
    (type: 'install' | 'activate', listener: (event: ExtendableEvent) => void): void;
    (type: 'fetch', listener: (event: FetchEvent) => void): void;
    (type: 'message', listener: (event: MessageEventLike) => void): void;
  };
};

const scope = self as unknown as WorkerScope;
const state = createMediaWorkerState();
const waiters = new Map<string, Set<() => void>>();
const sodiumReady: Promise<Sodium> = sodiumLib.ready.then(() =>
  createWrappersSodium(sodiumLib, () => {
    throw new Error('argon2id is not available in the media worker');
  }),
);

function wake(playbackId: string): void {
  for (const resolve of waiters.get(playbackId) ?? []) resolve();
  waiters.delete(playbackId);
}

async function ask(clientId: string, message: WorkerToPageMessage & { readonly playbackId: string }): Promise<void> {
  const answered = new Promise<void>((resolve) => {
    const set = waiters.get(message.playbackId) ?? new Set<() => void>();
    set.add(resolve);
    waiters.set(message.playbackId, set);
    setTimeout(resolve, SW_KEY_REQUEST_TIMEOUT_MS);
  });
  const client = clientId === '' ? undefined : await scope.clients.get(clientId);
  const targets = client === undefined ? await scope.clients.matchAll({ type: 'window' }) : [client];
  for (const target of targets) target.postMessage(message);
  await answered;
}

scope.addEventListener('install', (event) => event.waitUntil(scope.skipWaiting()));
scope.addEventListener('activate', (event) => event.waitUntil(scope.clients.claim()));

scope.addEventListener('message', (event) => {
  const message = event.data;
  switch (message.type) {
    case 'register':
      forgetPlayback(state, message.playbackId);
      state.playbacks.set(message.playbackId, {
        key: new Uint8Array(message.key),
        objectId: message.objectId,
        downloadUrl: message.downloadUrl,
        extraHeaders: message.extraHeaders,
        plaintextSize: message.plaintextSize,
        ciphertextSize: message.ciphertextSize,
        mimeType: message.mimeType,
        prepared: null,
      });
      wake(message.playbackId);
      return;
    case 'refreshUrl': {
      const playback = state.playbacks.get(message.playbackId);
      if (playback !== undefined) playback.downloadUrl = message.downloadUrl;
      wake(message.playbackId);
      return;
    }
    case 'forget':
      forgetPlayback(state, message.playbackId);
      return;
    case 'forgetAll':
      forgetAllPlaybacks(state);
      return;
    case 'ping':
      event.source?.postMessage({ type: 'pong', nonce: message.nonce });
      return;
  }
});

scope.addEventListener('fetch', (event) => {
  if (playbackIdOf(event.request.url) === null) return;
  event.respondWith(
    sodiumReady.then((sodium) =>
      handleMediaRequest(event.request, state, {
        sodium,
        fetch: (url, init) => fetch(url, { headers: init.headers }),
        requestKey: (playbackId) => ask(event.clientId, { type: 'needKey', playbackId }),
        requestUrl: (playbackId) => ask(event.clientId, { type: 'needUrl', playbackId }),
      }),
    ),
  );
});

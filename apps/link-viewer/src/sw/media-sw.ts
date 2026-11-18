import '../zod-config.ts';
import { loadSodium } from '../sodium.ts';
import { SW_KEY_REQUEST_TIMEOUT_MS } from '../constants.ts';
import {
  createMediaWorkerState,
  forgetAllPlaybacks,
  forgetPlayback,
  handleMediaRequest,
  playbackIdOf,
} from './media-handler.ts';
import { MEDIA_MESSAGE, type PageToWorkerMessage, type WorkerToPageMessage } from './messages.ts';

type MessageTarget = { readonly postMessage: (message: WorkerToPageMessage) => void };
type WindowClient = MessageTarget & { readonly id: string };
type ExtendableEvent = { readonly waitUntil: (work: Promise<unknown>) => void };
type FetchEvent = ExtendableEvent & {
  readonly request: Request;
  readonly clientId: string;
  readonly respondWith: (response: Promise<Response>) => void;
};
type MessageEventLike = {
  readonly data: PageToWorkerMessage;
  readonly source: MessageTarget | null;
};
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
const sodiumReady = loadSodium();

function wake(playbackId: string): void {
  for (const resolve of waiters.get(playbackId) ?? []) resolve();
  waiters.delete(playbackId);
}

async function ask(
  clientId: string,
  message: WorkerToPageMessage & { readonly playbackId: string },
): Promise<void> {
  const answered = new Promise<void>((resolve) => {
    const set = waiters.get(message.playbackId) ?? new Set<() => void>();
    set.add(resolve);
    waiters.set(message.playbackId, set);
    setTimeout(resolve, SW_KEY_REQUEST_TIMEOUT_MS);
  });
  const client = clientId === '' ? undefined : await scope.clients.get(clientId);
  const targets =
    client === undefined ? await scope.clients.matchAll({ type: 'window' }) : [client];
  for (const target of targets) target.postMessage(message);
  await answered;
}

function onMessage(event: MessageEventLike): void {
  const message = event.data;
  switch (message.type) {
    case MEDIA_MESSAGE.register:
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
    case MEDIA_MESSAGE.refreshUrl: {
      const playback = state.playbacks.get(message.playbackId);
      if (playback !== undefined) playback.downloadUrl = message.downloadUrl;
      wake(message.playbackId);
      return;
    }
    case MEDIA_MESSAGE.forget:
      forgetPlayback(state, message.playbackId);
      return;
    case MEDIA_MESSAGE.forgetAll:
      forgetAllPlaybacks(state);
      return;
    case MEDIA_MESSAGE.ping:
      event.source?.postMessage({ type: MEDIA_MESSAGE.pong, nonce: message.nonce });
      return;
  }
}

scope.addEventListener('install', (event) => event.waitUntil(scope.skipWaiting()));
scope.addEventListener('activate', (event) => event.waitUntil(scope.clients.claim()));
scope.addEventListener('message', onMessage);
scope.addEventListener('fetch', (event) => {
  if (playbackIdOf(event.request.url) === null) return;
  event.respondWith(
    sodiumReady.then((sodium) =>
      handleMediaRequest(event.request, state, {
        sodium,
        fetch: (url, init) => fetch(url, { headers: init.headers, credentials: 'omit' }),
        requestKey: (playbackId) =>
          ask(event.clientId, { type: MEDIA_MESSAGE.needKey, playbackId }),
        requestUrl: (playbackId) =>
          ask(event.clientId, { type: MEDIA_MESSAGE.needUrl, playbackId }),
      }),
    ),
  );
});

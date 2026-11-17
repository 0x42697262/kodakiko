import { PAGE_HIDE_EVENT, SW_CONTROL_TIMEOUT_MS, SW_PING_TIMEOUT_MS } from './constants.ts';
import { MEDIA_WORKER_SCOPE, MEDIA_WORKER_SCRIPT } from './keys.ts';
import {
  MEDIA_MESSAGE,
  type PageToWorkerMessage,
  type WorkerToPageMessage,
} from './sw/messages.ts';

export type MediaWorkerHandlers = {
  readonly onNeedKey: (playbackId: string) => void;
  readonly onNeedUrl: (playbackId: string) => void;
};

export type MediaWorker = {
  readonly isAvailable: () => Promise<boolean>;
  readonly post: (message: PageToWorkerMessage, transfer?: Transferable[]) => void;
};

export type PageEvents = Pick<EventTarget, 'addEventListener' | 'removeEventListener'>;

export type WorkerControllerHost = {
  readonly controller: { readonly postMessage: (message: PageToWorkerMessage) => void } | null;
};

export function forgetMediaOnPageHide(page: PageEvents, host: WorkerControllerHost): () => void {
  const forgetAll = (): void => host.controller?.postMessage({ type: MEDIA_MESSAGE.forgetAll });
  page.addEventListener(PAGE_HIDE_EVENT, forgetAll);
  return () => {
    page.removeEventListener(PAGE_HIDE_EVENT, forgetAll);
    forgetAll();
  };
}

function timeout(milliseconds: number): Promise<false> {
  return new Promise((resolve) => setTimeout(() => resolve(false), milliseconds));
}

async function controlled(container: ServiceWorkerContainer): Promise<boolean> {
  await container.register(MEDIA_WORKER_SCRIPT, { scope: MEDIA_WORKER_SCOPE });
  await container.ready;
  if (container.controller !== null) return true;
  const changed = new Promise<true>((resolve) =>
    container.addEventListener('controllerchange', () => resolve(true), { once: true }),
  );
  return Promise.race([changed, timeout(SW_CONTROL_TIMEOUT_MS)]);
}

export function createMediaWorker(handlers: MediaWorkerHandlers, nonce: () => string): MediaWorker {
  let availability: Promise<boolean> | null = null;
  const pongs = new Map<string, () => void>();

  function post(message: PageToWorkerMessage, transfer: Transferable[] = []): void {
    navigator.serviceWorker.controller?.postMessage(message, transfer);
  }

  function onMessage(event: MessageEvent<WorkerToPageMessage>): void {
    const message = event.data;
    if (message.type === MEDIA_MESSAGE.pong) pongs.get(message.nonce)?.();
    else if (message.type === MEDIA_MESSAGE.needKey) handlers.onNeedKey(message.playbackId);
    else if (message.type === MEDIA_MESSAGE.needUrl) handlers.onNeedUrl(message.playbackId);
  }

  async function check(): Promise<boolean> {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return false;
    const container = navigator.serviceWorker;
    try {
      container.addEventListener('message', onMessage);
      if (!(await controlled(container))) return false;
      const id = nonce();
      const answered = new Promise<true>((resolve) => pongs.set(id, () => resolve(true)));
      post({ type: MEDIA_MESSAGE.ping, nonce: id });
      const result = await Promise.race([answered, timeout(SW_PING_TIMEOUT_MS)]);
      pongs.delete(id);
      return result;
    } catch {
      return false;
    }
  }

  return {
    isAvailable: () => {
      availability ??= check();
      return availability;
    },
    post,
  };
}

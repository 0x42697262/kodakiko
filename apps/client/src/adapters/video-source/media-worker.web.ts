import type { PageToWorkerMessage, WorkerToPageMessage } from '../../../web/sw/messages';
import {
  MEDIA_WORKER_SCOPE,
  MEDIA_WORKER_SCRIPT,
  SW_CONTROL_TIMEOUT_MS,
  SW_PING_TIMEOUT_MS,
} from '../../constants/video-constants';

export type MediaWorkerHandlers = {
  readonly onNeedKey: (playbackId: string) => void;
  readonly onNeedUrl: (playbackId: string) => void;
};

export type MediaWorker = {
  readonly isAvailable: () => Promise<boolean>;
  readonly post: (message: PageToWorkerMessage, transfer?: Transferable[]) => void;
};

function timeout(ms: number): Promise<false> {
  return new Promise((resolve) => setTimeout(() => resolve(false), ms));
}

async function controlled(): Promise<boolean> {
  const container = navigator.serviceWorker;
  await container.register(MEDIA_WORKER_SCRIPT, { scope: MEDIA_WORKER_SCOPE });
  await container.ready;
  if (container.controller !== null) return true;
  const changed = new Promise<true>((resolve) => container.addEventListener('controllerchange', () => resolve(true), { once: true }));
  return Promise.race([changed, timeout(SW_CONTROL_TIMEOUT_MS)]);
}

export function createMediaWorker(handlers: MediaWorkerHandlers, nonce: () => string): MediaWorker {
  let availability: Promise<boolean> | null = null;
  const pongs = new Map<string, () => void>();

  function post(message: PageToWorkerMessage, transfer: Transferable[] = []): void {
    navigator.serviceWorker?.controller?.postMessage(message, transfer);
  }

  function listen(): void {
    navigator.serviceWorker.addEventListener('message', (event: MessageEvent<WorkerToPageMessage>) => {
      const message = event.data;
      if (message.type === 'pong') pongs.get(message.nonce)?.();
      else if (message.type === 'needKey') handlers.onNeedKey(message.playbackId);
      else if (message.type === 'needUrl') handlers.onNeedUrl(message.playbackId);
    });
  }

  async function check(): Promise<boolean> {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return false;
    try {
      listen();
      if (!(await controlled())) return false;
      const id = nonce();
      const answered = new Promise<true>((resolve) => pongs.set(id, () => resolve(true)));
      post({ type: 'ping', nonce: id });
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

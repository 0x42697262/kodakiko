import { HEX_RADIX, HEX_WIDTH, PLAYBACK_ID_BYTES } from './constants.ts';
import { createElement } from './dom.ts';
import { playbackMode } from './download.ts';
import { CSS_CLASSES, ELEMENT_ROLES, LINK_VIEWER_IDS, MEDIA_PATH_PREFIX } from './keys.ts';
import type { LinkItem, LinkSession } from './link-session.ts';
import type { MediaWorker } from './media-worker.ts';
import { safeBlobMimeType } from './object-urls.ts';
import { MEDIA_MESSAGE } from './sw/messages.ts';
import { TEXT } from './text.ts';
import type { Sodium } from '@kodakiko/core';

export type Playbacks = {
  readonly register: (item: LinkItem, playbackId: string) => void;
  readonly forget: (playbackId: string) => void;
  readonly onNeedKey: (playbackId: string) => void;
  readonly onNeedUrl: (playbackId: string) => void;
};

export function hexId(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(HEX_RADIX).padStart(HEX_WIDTH, '0')).join('');
}

export function createPlaybacks(session: LinkSession, worker: () => MediaWorker): Playbacks {
  const items = new Map<string, LinkItem>();

  function register(item: LinkItem, playbackId: string): void {
    const registration = session.video(item);
    items.set(playbackId, item);
    const key = registration.key.slice().buffer;
    registration.key.fill(0);
    worker().post(
      {
        type: MEDIA_MESSAGE.register,
        playbackId,
        key,
        objectId: registration.objectId,
        downloadUrl: registration.downloadUrl,
        extraHeaders: registration.extraHeaders,
        plaintextSize: registration.plaintextSize,
        ciphertextSize: registration.ciphertextSize,
        mimeType: safeBlobMimeType(registration.mimeType),
      },
      [key],
    );
  }

  return {
    register,
    forget: (playbackId) => {
      items.delete(playbackId);
      worker().post({ type: MEDIA_MESSAGE.forget, playbackId });
    },
    onNeedKey: (playbackId) => {
      const item = items.get(playbackId);
      if (item !== undefined) register(item, playbackId);
    },
    onNeedUrl: (playbackId) => {
      const item = items.get(playbackId);
      if (item === undefined) return;
      session
        .refreshUrl(item.original.id)
        .then((downloadUrl) => {
          if (downloadUrl !== null)
            worker().post({ type: MEDIA_MESSAGE.refreshUrl, playbackId, downloadUrl });
        })
        .catch(() => undefined);
    },
  };
}

export type VideoPaneDeps = {
  readonly sodium: Sodium;
  readonly session: LinkSession;
  readonly worker: MediaWorker;
  readonly playbacks: Playbacks;
};

export type VideoPane = { readonly element: HTMLElement; readonly release: () => void };

async function source(
  item: LinkItem,
  deps: VideoPaneDeps,
  onRelease: (release: () => void) => void,
): Promise<string | null> {
  const mode = playbackMode(
    await deps.worker.isAvailable(),
    item.descriptor.originalPlaintextBytes,
  );
  if (mode === 'unsupported') return null;
  if (mode === 'stream') {
    const playbackId = hexId(deps.sodium.randomBytes(PLAYBACK_ID_BYTES));
    deps.playbacks.register(item, playbackId);
    onRelease(() => deps.playbacks.forget(playbackId));
    return `${MEDIA_PATH_PREFIX}${playbackId}`;
  }
  const bytes = await deps.session.original(item);
  const url = URL.createObjectURL(new Blob([bytes.slice()], { type: safeBlobMimeType(item.descriptor.mimeType) }));
  onRelease(() => URL.revokeObjectURL(url));
  return url;
}

export function createVideoPane(item: LinkItem, deps: VideoPaneDeps): VideoPane {
  const element = createElement('div', {
    testId: LINK_VIEWER_IDS.videoPlayer,
    className: CSS_CLASSES.player,
  });
  const spinner = createElement('div', {
    testId: LINK_VIEWER_IDS.spinner,
    className: CSS_CLASSES.spinner,
    attributes: { role: ELEMENT_ROLES.progressbar, 'aria-label': TEXT.loading },
  });
  element.append(spinner);
  let released = false;
  const releases: (() => void)[] = [];
  const keep = (release: () => void) => {
    if (released) release();
    else releases.push(release);
  };
  const unsupported = () =>
    element.replaceChildren(
      createElement('p', { className: CSS_CLASSES.hint, text: TEXT.videoUnsupported }),
    );
  source(item, deps, keep)
    .then((url) => {
      if (released) return;
      if (url === null) {
        unsupported();
        return;
      }
      const video = createElement('video', {
        attributes: { controls: '', playsinline: '', preload: 'metadata' },
      });
      video.addEventListener('error', unsupported, { once: true });
      video.src = url;
      element.replaceChildren(video);
      video.play().catch(() => undefined);
    })
    .catch(() => {
      if (!released) unsupported();
    });
  return {
    element,
    release: () => {
      released = true;
      const video = element.querySelector('video');
      if (video !== null) {
        video.pause();
        video.removeAttribute('src');
        video.load();
      }
      for (const release of releases.splice(0)) release();
    },
  };
}

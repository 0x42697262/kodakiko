import type { Sodium } from '@kodakiko/core';
import {
  OBJECT_URL_CACHE_ITEMS,
  ORIGINAL_CACHE_ITEMS,
  PLAYBACK_ID_BYTES,
  THUMBNAIL_CONCURRENCY,
} from './constants.ts';
import { todayKey } from './day-groups.ts';
import { createDetailView } from './detail-view.ts';
import { createElement } from './dom.ts';
import { createGalleryView, viewerOrder } from './gallery-view.ts';
import { createHttpClient } from './http.ts';
import { CSS_CLASSES, ELEMENT_ROLES, LINK_VIEWER_IDS } from './keys.ts';
import { failureKind, failureMessage } from './link-errors.ts';
import { createLinkSession, type LinkSession, type OpenedLink } from './link-session.ts';
import { parseLinkLocation } from './link-url.ts';
import { createMediaWorker, type MediaWorker } from './media-worker.ts';
import { createObjectUrlCache } from './object-urls.ts';
import { loadSodium } from './sodium.ts';
import { createTaskQueue } from './task-queue.ts';
import { TEXT } from './text.ts';
import { createPlaybacks, hexId, type Playbacks } from './video-pane.ts';

export type ViewerEnvironment = {
  readonly root: HTMLElement;
  readonly location: Location;
  readonly history: History;
  readonly now: () => Date;
};

const DETAIL_STATE = Object.freeze({ detail: true });

function showLoading(root: HTMLElement): void {
  root.replaceChildren(
    createElement('div', {
      className: CSS_CLASSES.status,
      attributes: { role: ELEMENT_ROLES.status },
      children: [
        createElement('div', {
          testId: LINK_VIEWER_IDS.spinner,
          className: CSS_CLASSES.spinner,
          attributes: { 'aria-hidden': 'true' },
        }),
        createElement('p', { text: TEXT.loading }),
      ],
    }),
  );
}

function showFailure(root: HTMLElement, message: string): void {
  root.replaceChildren(
    createElement('div', {
      className: CSS_CLASSES.status,
      attributes: { role: ELEMENT_ROLES.alert },
      children: [createElement('p', { testId: LINK_VIEWER_IDS.message, text: message })],
    }),
  );
}

type LinkContext = {
  readonly sodium: Sodium;
  readonly session: LinkSession;
  readonly worker: MediaWorker;
  readonly playbacks: Playbacks;
};

function showLink(environment: ViewerEnvironment, link: OpenedLink, context: LinkContext): void {
  const { sodium, session, worker, playbacks } = context;
  const items = viewerOrder(link.items);
  const today = todayKey(environment.now());
  const thumbnails = createObjectUrlCache(OBJECT_URL_CACHE_ITEMS);
  const originals = createObjectUrlCache(ORIGINAL_CACHE_ITEMS);
  let pendingBacks = 0;
  const detail = createDetailView({
    items,
    maxViews: link.maxViews,
    today,
    sodium,
    session,
    worker,
    playbacks,
    thumbnails,
    originals,
    onClose: (index) => {
      gallery.focusItem(index);
      pendingBacks += 1;
      environment.history.back();
    },
  });
  const gallery = createGalleryView({
    title: link.title,
    items,
    today,
    session,
    thumbnails,
    queue: createTaskQueue(THUMBNAIL_CONCURRENCY),
    onOpen: (index) => {
      if (!detail.isOpen())
        environment.history.pushState(DETAIL_STATE, '', environment.location.pathname);
      detail.show(index);
    },
  });
  window.addEventListener('popstate', () => {
    if (pendingBacks > 0) {
      pendingBacks -= 1;
      return;
    }
    if (detail.isOpen()) detail.hide();
  });
  document.title = link.title;
  environment.root.replaceChildren(gallery.element, detail.element);
}

export async function startViewer(environment: ViewerEnvironment): Promise<void> {
  const { root, location, history } = environment;
  showLoading(root);
  const parsed = parseLinkLocation(location.pathname, location.hash);
  history.replaceState(null, '', location.pathname);
  if (!parsed.ok) {
    showFailure(root, failureMessage(parsed.failure));
    return;
  }
  let sodium: Sodium;
  try {
    sodium = await loadSodium();
  } catch (error) {
    parsed.secret.fill(0);
    showFailure(root, failureMessage(failureKind(error, null)));
    return;
  }
  const http = createHttpClient({ baseUrl: location.origin });
  const session = createLinkSession({
    sodium,
    http,
    linkId: parsed.linkId,
    secret: parsed.secret,
    origin: location.origin,
  });
  parsed.secret.fill(0);
  const playbacks = createPlaybacks(session, () => worker);
  const worker: MediaWorker = createMediaWorker(
    {
      onNeedKey: (playbackId) => playbacks.onNeedKey(playbackId),
      onNeedUrl: (playbackId) => playbacks.onNeedUrl(playbackId),
    },
    () => hexId(sodium.randomBytes(PLAYBACK_ID_BYTES)),
  );
  void worker.isAvailable();
  try {
    showLink(environment, await session.open(), { sodium, session, worker, playbacks });
  } catch (error) {
    showFailure(root, failureMessage(failureKind(error, null)));
  }
}

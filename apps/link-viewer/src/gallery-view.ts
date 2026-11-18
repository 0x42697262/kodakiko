import { formatDayHeader, groupByDay } from './day-groups.ts';
import { createElement } from './dom.ts';
import { CSS_CLASSES, LINK_VIEWER_IDS, PRELOAD_ROOT_MARGIN } from './keys.ts';
import type { LinkItem, LinkSession } from './link-session.ts';
import type { ObjectUrlCache } from './object-urls.ts';
import type { TaskQueue } from './task-queue.ts';
import { TEXT } from './text.ts';

export type GalleryDeps = {
  readonly title: string;
  readonly items: readonly LinkItem[];
  readonly today: string;
  readonly session: LinkSession;
  readonly thumbnails: ObjectUrlCache;
  readonly queue: TaskQueue;
  readonly onOpen: (index: number) => void;
};

export type GalleryView = {
  readonly element: HTMLElement;
  readonly focusItem: (index: number) => void;
};

export function viewerOrder(items: readonly LinkItem[]): LinkItem[] {
  return groupByDay(items).flatMap((group) => group.items);
}

function loadThumbnail(tile: HTMLElement, item: LinkItem, index: number, deps: GalleryDeps): void {
  deps.queue
    .run(() => deps.session.thumbnail(item))
    .then((bytes) => {
      const url = deps.thumbnails.urlFor(
        item.thumbnail.id,
        bytes,
        item.descriptor.thumbnail.mimeType,
      );
      const label =
        item.descriptor.kind === 'video' ? TEXT.videoLabel(index + 1) : TEXT.photoLabel(index + 1);
      tile.prepend(
        createElement('img', {
          className: CSS_CLASSES.tileImage,
          attributes: { src: url, alt: label, draggable: 'false' },
        }),
      );
    })
    .catch(() => undefined);
}

function createLoader(
  deps: GalleryDeps,
): (tile: HTMLElement, item: LinkItem, index: number) => void {
  if (typeof IntersectionObserver === 'undefined')
    return (tile, item, index) => loadThumbnail(tile, item, index, deps);
  const pending = new Map<Element, () => void>();
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        observer.unobserve(entry.target);
        pending.get(entry.target)?.();
        pending.delete(entry.target);
      }
    },
    { rootMargin: PRELOAD_ROOT_MARGIN },
  );
  return (tile, item, index) => {
    pending.set(tile, () => loadThumbnail(tile, item, index, deps));
    observer.observe(tile);
  };
}

export function createGalleryView(deps: GalleryDeps): GalleryView {
  const load = createLoader(deps);
  const tiles: HTMLButtonElement[] = [];
  const sections = groupByDay(deps.items).map((group, dayIndex) => {
    const grid = createElement('div', { className: CSS_CLASSES.grid });
    for (const item of group.items) {
      const index = tiles.length;
      const tile = createElement('button', {
        testId: LINK_VIEWER_IDS.item(index),
        className: CSS_CLASSES.tile,
        attributes: {
          type: 'button',
          'aria-label':
            item.descriptor.kind === 'video'
              ? TEXT.videoLabel(index + 1)
              : TEXT.photoLabel(index + 1),
        },
      });
      if (item.descriptor.kind === 'video')
        tile.append(
          createElement('span', {
            className: CSS_CLASSES.badge,
            text: TEXT.videoBadge,
            attributes: { 'aria-hidden': 'true' },
          }),
        );
      tile.addEventListener('click', () => deps.onOpen(index));
      tiles.push(tile);
      grid.append(tile);
      load(tile, item, index);
    }
    const header = createElement('h2', {
      testId: LINK_VIEWER_IDS.day(dayIndex),
      className: CSS_CLASSES.dayHeader,
      text: formatDayHeader(group.dayKey, deps.today),
    });
    return createElement('section', { className: CSS_CLASSES.day, children: [header, grid] });
  });
  const title = createElement('h1', {
    testId: LINK_VIEWER_IDS.title,
    className: CSS_CLASSES.title,
    text: deps.title,
  });
  const footer = createElement('footer', {
    testId: LINK_VIEWER_IDS.footer,
    className: CSS_CLASSES.footer,
    text: TEXT.footer,
  });
  const element = createElement('div', {
    className: CSS_CLASSES.page,
    children: [
      createElement('header', { className: CSS_CLASSES.header, children: [title] }),
      ...sections,
      footer,
    ],
  });
  return { element, focusItem: (index) => tiles[index]?.focus({ preventScroll: false }) };
}

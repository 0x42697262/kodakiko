import type { Sodium } from '@kodakiko/core';
import { formatDayHeader, localDayKey } from './day-groups.ts';
import { createButton, createElement } from './dom.ts';
import { canDownload, downloadName } from './download.ts';
import { attachGestures, type Gestures } from './gestures.ts';
import { CSS_CLASSES, ELEMENT_ROLES, LINK_VIEWER_IDS, SELECTORS } from './keys.ts';
import { failureKind, failureMessage } from './link-errors.ts';
import type { LinkItem, LinkSession } from './link-session.ts';
import type { MediaWorker } from './media-worker.ts';
import type { ObjectUrlCache } from './object-urls.ts';
import { TEXT } from './text.ts';
import { createVideoPane, type Playbacks, type VideoPane } from './video-pane.ts';
import { canMove, keyCommand, stepIndex, type Direction } from './viewer-model.ts';

export type DetailDeps = {
  readonly items: readonly LinkItem[];
  readonly maxViews: number | null;
  readonly today: string;
  readonly sodium: Sodium;
  readonly session: LinkSession;
  readonly worker: MediaWorker;
  readonly playbacks: Playbacks;
  readonly thumbnails: ObjectUrlCache;
  readonly originals: ObjectUrlCache;
  readonly onClose: (index: number) => void;
};

export type DetailView = {
  readonly element: HTMLElement;
  readonly show: (index: number) => void;
  readonly hide: () => void;
  readonly isOpen: () => boolean;
};

function originalKey(item: LinkItem): string {
  return item.original.id;
}

export function createDetailView(deps: DetailDeps): DetailView {
  const { items } = deps;
  let index = 0;
  let open = false;
  let generation = 0;
  let video: VideoPane | null = null;

  const back = createButton(TEXT.back, LINK_VIEWER_IDS.back, CSS_CLASSES.button, () => close());
  const previous = createButton(TEXT.previous, LINK_VIEWER_IDS.previous, CSS_CLASSES.button, () =>
    move('previous'),
  );
  const counter = createElement('span', {
    testId: LINK_VIEWER_IDS.counter,
    className: CSS_CLASSES.counter,
    attributes: { 'aria-live': 'polite' },
  });
  const next = createButton(TEXT.next, LINK_VIEWER_IDS.next, CSS_CLASSES.button, () =>
    move('next'),
  );
  const download = createButton(
    TEXT.download,
    LINK_VIEWER_IDS.download,
    CSS_CLASSES.button,
    () => void save(),
  );
  const toolbar = createElement('div', {
    className: CSS_CLASSES.toolbar,
    children: [back, previous, counter, next, download],
  });
  const media = createElement('div', {
    testId: LINK_VIEWER_IDS.media,
    className: CSS_CLASSES.media,
  });
  const date = createElement('p', { testId: LINK_VIEWER_IDS.date, className: CSS_CLASSES.date });
  const hint = createElement('p', { className: CSS_CLASSES.hint });
  const caption = createElement('div', { className: CSS_CLASSES.caption, children: [date] });
  const element = createElement('div', {
    testId: LINK_VIEWER_IDS.detail,
    className: CSS_CLASSES.detail,
    attributes: { role: ELEMENT_ROLES.dialog, 'aria-modal': 'true' },
    children: [toolbar, media, caption],
  });
  element.hidden = true;
  const gestures: Gestures = attachGestures(media, (direction) => move(direction));

  function current(): LinkItem | undefined {
    return items[index];
  }

  function releaseVideo(): void {
    video?.release();
    video = null;
  }

  function showMessage(error: unknown): void {
    media.replaceChildren(
      createElement('p', {
        className: CSS_CLASSES.hint,
        text: failureMessage(failureKind(error, deps.maxViews)),
      }),
    );
  }

  function spinner(): HTMLElement {
    return createElement('div', {
      testId: LINK_VIEWER_IDS.spinner,
      className: CSS_CLASSES.spinner,
      attributes: { role: ELEMENT_ROLES.progressbar, 'aria-label': TEXT.loading },
    });
  }

  function renderPhoto(item: LinkItem, shown: number): void {
    const image = createElement('img', {
      className: CSS_CLASSES.photo,
      attributes: { alt: TEXT.photoLabel(index + 1), draggable: 'false' },
    });
    const placeholder =
      deps.thumbnails.get(item.thumbnail.id) ?? deps.originals.get(originalKey(item));
    const cached = deps.originals.get(originalKey(item));
    if (placeholder !== undefined) image.src = cached ?? placeholder;
    media.replaceChildren(image, ...(cached === undefined ? [spinner()] : []));
    gestures.setTarget(image, { width: item.descriptor.width, height: item.descriptor.height });
    if (cached !== undefined) return;
    deps.session
      .original(item)
      .then((bytes) => {
        if (shown !== generation) return;
        image.src = deps.originals.urlFor(originalKey(item), bytes, item.descriptor.mimeType);
        media.querySelector(`.${CSS_CLASSES.spinner}`)?.remove();
      })
      .catch((error: unknown) => {
        if (shown === generation) showMessage(error);
      });
  }

  function renderVideo(item: LinkItem): void {
    gestures.setTarget(null);
    const play = createButton(
      TEXT.play,
      LINK_VIEWER_IDS.play,
      `${CSS_CLASSES.button} ${CSS_CLASSES.primary}`,
      () => {
        releaseVideo();
        video = createVideoPane(item, deps);
        media.replaceChildren(video.element);
      },
    );
    play.setAttribute('aria-label', TEXT.videoLabel(index + 1));
    const poster = deps.thumbnails.get(item.thumbnail.id);
    const children: Node[] = [];
    if (poster !== undefined)
      children.push(
        createElement('img', {
          className: CSS_CLASSES.photo,
          attributes: { src: poster, alt: '', draggable: 'false' },
        }),
      );
    children.push(play);
    media.replaceChildren(...children);
  }

  function renderCaption(item: LinkItem): void {
    date.textContent = formatDayHeader(
      localDayKey(item.takenAtUtc, item.utcOffsetMinutes),
      deps.today,
    );
    const parts: Node[] = [date];
    if (!canDownload(item.descriptor)) {
      hint.textContent = TEXT.downloadTooLarge;
      parts.push(hint);
    }
    if (item.note.length > 0)
      parts.push(
        createElement('p', {
          testId: LINK_VIEWER_IDS.note,
          className: CSS_CLASSES.note,
          text: item.note,
        }),
      );
    caption.replaceChildren(...parts);
  }

  function render(): void {
    const item = current();
    if (item === undefined) return;
    generation += 1;
    releaseVideo();
    counter.textContent = TEXT.counter(index + 1, items.length);
    previous.disabled = !canMove(index, items.length, 'previous');
    next.disabled = !canMove(index, items.length, 'next');
    download.disabled = !canDownload(item.descriptor);
    if (item.descriptor.kind === 'photo') renderPhoto(item, generation);
    else renderVideo(item);
    renderCaption(item);
  }

  function move(direction: Direction): void {
    const target = stepIndex(index, items.length, direction);
    if (target === index) return;
    index = target;
    render();
  }

  async function save(): Promise<void> {
    const item = current();
    if (item === undefined || !canDownload(item.descriptor)) return;
    const position = index;
    download.disabled = true;
    try {
      const bytes = await deps.session.original(item);
      const url = URL.createObjectURL(
        new Blob([bytes.slice()], { type: item.descriptor.mimeType }),
      );
      const anchor = createElement('a', {
        attributes: { href: url, download: downloadName(position, item.descriptor) },
      });
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (error) {
      if (current() === item) showMessage(error);
    } finally {
      if (current() === item) download.disabled = false;
    }
  }

  function onKey(event: KeyboardEvent): void {
    if (event.target instanceof Element && event.target.closest(SELECTORS.textEntry) !== null)
      return;
    const command = keyCommand(event.key);
    if (command === null) return;
    event.preventDefault();
    if (command === 'close') close();
    else move(command);
  }

  function hide(): void {
    if (!open) return;
    open = false;
    generation += 1;
    releaseVideo();
    gestures.setTarget(null);
    media.replaceChildren();
    element.hidden = true;
    document.removeEventListener('keydown', onKey);
    document.body.classList.remove(CSS_CLASSES.noScroll);
  }

  function close(): void {
    const closedAt = index;
    hide();
    deps.onClose(closedAt);
  }

  return {
    element,
    show: (target) => {
      index = target;
      if (!open) {
        open = true;
        element.hidden = false;
        document.addEventListener('keydown', onKey);
        document.body.classList.add(CSS_CLASSES.noScroll);
      }
      render();
      back.focus();
    },
    hide,
    isOpen: () => open,
  };
}

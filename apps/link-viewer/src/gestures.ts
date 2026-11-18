import {
  DOUBLE_TAP_MS,
  HALF,
  PINCH_POINTERS,
  TAP_MAX_DISTANCE_PX,
  WHEEL_ZOOM_SENSITIVITY,
  ZOOM_DOUBLE_TAP,
  ZOOM_UNZOOMED,
} from './constants.ts';
import { SELECTORS } from './keys.ts';
import { swipeDirection, type Direction } from './viewer-model.ts';
import {
  clampScale,
  clampTranslate,
  maxTranslate,
  pinchScale,
  settleScale,
  zoomAround,
} from './zoom-math.ts';

type Point = { readonly x: number; readonly y: number };
export type ContentSize = { readonly width: number; readonly height: number };
type Mode = 'idle' | 'swipe' | 'pan' | 'pinch';

export type Gestures = {
  readonly setTarget: (target: HTMLElement | null, content?: ContentSize) => void;
  readonly detach: () => void;
};

function distance(first: Point, second: Point): number {
  return Math.hypot(first.x - second.x, first.y - second.y);
}

function midpoint(first: Point, second: Point): Point {
  return { x: (first.x + second.x) / HALF, y: (first.y + second.y) / HALF };
}

export function attachGestures(
  area: HTMLElement,
  onSwipe: (direction: Direction) => void,
): Gestures {
  const pointers = new Map<number, Point>();
  let target: HTMLElement | null = null;
  let content: ContentSize | null = null;
  let mode: Mode = 'idle';
  let scale = ZOOM_UNZOOMED;
  let translate: Point = { x: 0, y: 0 };
  let start: Point = { x: 0, y: 0 };
  let startTranslate: Point = { x: 0, y: 0 };
  let startScale = ZOOM_UNZOOMED;
  let startDistance = 0;
  let moved = 0;
  let lastTapAt = Number.NEGATIVE_INFINITY;

  function center(): Point {
    const box = area.getBoundingClientRect();
    return { x: box.left + box.width / HALF, y: box.top + box.height / HALF };
  }

  function displayed(element: HTMLElement): ContentSize {
    if (content === null || content.width <= 0 || content.height <= 0)
      return { width: element.offsetWidth, height: element.offsetHeight };
    const fit = Math.min(
      element.offsetWidth / content.width,
      element.offsetHeight / content.height,
    );
    return { width: content.width * fit, height: content.height * fit };
  }

  function limits(nextScale: number): Point {
    if (target === null) return { x: 0, y: 0 };
    const size = displayed(target);
    return {
      x: maxTranslate(size.width, area.clientWidth, nextScale),
      y: maxTranslate(size.height, area.clientHeight, nextScale),
    };
  }

  function apply(nextScale: number, next: Point): void {
    const max = limits(nextScale);
    scale = nextScale;
    translate = { x: clampTranslate(next.x, max.x), y: clampTranslate(next.y, max.y) };
    if (target !== null)
      target.style.transform = `translate(${translate.x}px, ${translate.y}px) scale(${scale})`;
  }

  function zoomTo(nextScale: number, focal: Point): void {
    const middle = center();
    const ratio = nextScale / scale;
    apply(nextScale, {
      x: zoomAround(translate.x, focal.x, middle.x, ratio),
      y: zoomAround(translate.y, focal.y, middle.y, ratio),
    });
  }

  function begin(point: Point): void {
    start = point;
    startTranslate = translate;
    moved = 0;
    mode = scale > ZOOM_UNZOOMED ? 'pan' : 'swipe';
  }

  function beginPinch(): void {
    const [first, second] = [...pointers.values()];
    if (first === undefined || second === undefined || target === null) return;
    mode = 'pinch';
    start = midpoint(first, second);
    startTranslate = translate;
    startScale = scale;
    startDistance = distance(first, second);
  }

  function pinchMove(): void {
    const [first, second] = [...pointers.values()];
    if (first === undefined || second === undefined) return;
    const middle = center();
    const nextScale = pinchScale(startScale, startDistance, distance(first, second));
    const ratio = nextScale / startScale;
    const focal = midpoint(first, second);
    apply(nextScale, {
      x: zoomAround(startTranslate.x, start.x, middle.x, ratio) + focal.x - start.x,
      y: zoomAround(startTranslate.y, start.y, middle.y, ratio) + focal.y - start.y,
    });
  }

  function onPointerDown(event: PointerEvent): void {
    if (event.target instanceof Element && event.target.closest(SELECTORS.nativeMedia) !== null)
      return;
    const onControl =
      event.target instanceof Element && event.target.closest(SELECTORS.controls) !== null;
    if (event.pointerType === 'mouse' && !onControl) area.setPointerCapture(event.pointerId);
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pointers.size === 1) begin({ x: event.clientX, y: event.clientY });
    else if (pointers.size === PINCH_POINTERS) beginPinch();
  }

  function onPointerMove(event: PointerEvent): void {
    if (!pointers.has(event.pointerId)) return;
    const point = { x: event.clientX, y: event.clientY };
    pointers.set(event.pointerId, point);
    if (mode === 'pinch') pinchMove();
    else if (mode !== 'idle') {
      moved = Math.max(moved, distance(point, start));
      if (mode === 'pan')
        apply(scale, {
          x: startTranslate.x + point.x - start.x,
          y: startTranslate.y + point.y - start.y,
        });
    }
  }

  function tap(point: Point, at: number): void {
    if (target === null) return;
    if (at - lastTapAt > DOUBLE_TAP_MS) {
      lastTapAt = at;
      return;
    }
    lastTapAt = Number.NEGATIVE_INFINITY;
    if (scale > ZOOM_UNZOOMED) apply(ZOOM_UNZOOMED, { x: 0, y: 0 });
    else zoomTo(ZOOM_DOUBLE_TAP, point);
  }

  function finishSingle(event: PointerEvent): void {
    const point = { x: event.clientX, y: event.clientY };
    if (event.type === 'pointercancel') return;
    if (mode === 'swipe') {
      const direction = swipeDirection({ dx: point.x - start.x, dy: point.y - start.y });
      if (direction !== null) {
        onSwipe(direction);
        return;
      }
    }
    if (moved <= TAP_MAX_DISTANCE_PX) tap(point, event.timeStamp);
  }

  function onPointerUp(event: PointerEvent): void {
    if (!pointers.has(event.pointerId)) return;
    pointers.delete(event.pointerId);
    if (mode === 'pinch') {
      apply(settleScale(scale), translate);
      const [remaining] = [...pointers.values()];
      if (remaining === undefined) mode = 'idle';
      else {
        begin(remaining);
        moved = TAP_MAX_DISTANCE_PX + 1;
      }
      return;
    }
    if (pointers.size === 0) {
      finishSingle(event);
      mode = 'idle';
    }
  }

  function onWheel(event: WheelEvent): void {
    if (target === null) return;
    event.preventDefault();
    const nextScale = settleScale(
      clampScale(scale * Math.exp(-event.deltaY * WHEEL_ZOOM_SENSITIVITY)),
    );
    if (nextScale === ZOOM_UNZOOMED) apply(ZOOM_UNZOOMED, { x: 0, y: 0 });
    else zoomTo(nextScale, { x: event.clientX, y: event.clientY });
  }

  area.addEventListener('pointerdown', onPointerDown);
  area.addEventListener('pointermove', onPointerMove);
  area.addEventListener('pointerup', onPointerUp);
  area.addEventListener('pointercancel', onPointerUp);
  area.addEventListener('wheel', onWheel, { passive: false });

  return {
    setTarget: (next, size) => {
      target = next;
      content = size ?? null;
      pointers.clear();
      mode = 'idle';
      scale = ZOOM_UNZOOMED;
      translate = { x: 0, y: 0 };
      lastTapAt = Number.NEGATIVE_INFINITY;
    },
    detach: () => {
      area.removeEventListener('pointerdown', onPointerDown);
      area.removeEventListener('pointermove', onPointerMove);
      area.removeEventListener('pointerup', onPointerUp);
      area.removeEventListener('pointercancel', onPointerUp);
      area.removeEventListener('wheel', onWheel);
    },
  };
}

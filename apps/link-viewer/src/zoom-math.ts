import { HALF, ZOOM_MAX, ZOOM_MIN, ZOOM_SNAP_BELOW, ZOOM_UNZOOMED } from './constants.ts';

export function clampScale(scale: number): number {
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, scale));
}

export function settleScale(scale: number): number {
  return scale < ZOOM_SNAP_BELOW ? ZOOM_UNZOOMED : scale;
}

export function maxTranslate(displayedSize: number, viewportSize: number, scale: number): number {
  return Math.max(0, (displayedSize * scale - viewportSize) / HALF);
}

export function clampTranslate(value: number, max: number): number {
  return Math.min(max, Math.max(-max, value));
}

export function zoomAround(
  translate: number,
  focal: number,
  center: number,
  scaleRatio: number,
): number {
  return translate * scaleRatio + (focal - center) * (1 - scaleRatio);
}

export function pinchScale(startScale: number, startDistance: number, distance: number): number {
  if (startDistance <= 0) return startScale;
  return clampScale((startScale * distance) / startDistance);
}

import { HALF, ZOOM_MAX, ZOOM_MIN, ZOOM_SNAP_BELOW, ZOOM_UNZOOMED } from './constants';

export function clampScale(scale: number): number {
  'worklet';
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, scale));
}

export function settleScale(scale: number): number {
  'worklet';
  return scale < ZOOM_SNAP_BELOW ? ZOOM_UNZOOMED : scale;
}

export function maxTranslate(displayedSize: number, viewportSize: number, scale: number): number {
  'worklet';
  return Math.max(0, (displayedSize * scale - viewportSize) / HALF);
}

export function clampTranslate(value: number, max: number): number {
  'worklet';
  return Math.min(max, Math.max(-max, value));
}

export function zoomAround(translate: number, focal: number, center: number, scaleRatio: number): number {
  'worklet';
  return translate * scaleRatio + (focal - center) * (1 - scaleRatio);
}

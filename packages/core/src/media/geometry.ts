import { ORIENTATION_MAX, ORIENTATION_SWAP_MIN } from './constants.ts';

export type MediaSize = { readonly width: number; readonly height: number };

export function fitWithin(width: number, height: number, maxEdge: number): MediaSize {
  const longest = Math.max(width, height);
  const scale = longest > maxEdge ? maxEdge / longest : 1;
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  };
}

export function displaySize(
  width: number,
  height: number,
  orientation: number | null,
): MediaSize {
  const swapped =
    orientation !== null && orientation >= ORIENTATION_SWAP_MIN && orientation <= ORIENTATION_MAX;
  return swapped ? { width: height, height: width } : { width, height };
}

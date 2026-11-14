import { useSyncExternalStore } from 'react';

let hidden = false;
const listeners = new Set<() => void>();

export function setBarsHidden(next: boolean): void {
  if (next === hidden) return;
  hidden = next;
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useBarsHidden(): boolean {
  return useSyncExternalStore(subscribe, () => hidden, () => hidden);
}

export const SCROLL_HIDE_THRESHOLD = 24;

export function scrollDirectionHides(previousY: number, nextY: number): boolean | null {
  if (nextY <= SCROLL_HIDE_THRESHOLD) return false;
  if (Math.abs(nextY - previousY) < SCROLL_HIDE_THRESHOLD) return null;
  return nextY > previousY;
}

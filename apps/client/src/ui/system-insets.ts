import { useSyncExternalStore } from 'react';
import { Platform } from 'react-native';
import { useSafeAreaInsets, type EdgeInsets } from 'react-native-safe-area-context';

export const FULL_SCREEN_MIN_TOP = 28;
export const FULL_SCREEN_MIN_BOTTOM = 20;

let fullScreen = false;
const listeners = new Set<() => void>();

export function setFullScreenInsets(next: boolean): void {
  if (next === fullScreen) return;
  fullScreen = next;
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function systemInsets(insets: EdgeInsets, isFullScreen: boolean, isAndroid: boolean): EdgeInsets {
  if (!isFullScreen || !isAndroid) return insets;
  return { ...insets, top: Math.max(insets.top, FULL_SCREEN_MIN_TOP), bottom: Math.max(insets.bottom, FULL_SCREEN_MIN_BOTTOM) };
}

export function useSystemInsets(): EdgeInsets {
  const insets = useSafeAreaInsets();
  const current = useSyncExternalStore(subscribe, () => fullScreen, () => fullScreen);
  return systemInsets(insets, current, Platform.OS === 'android');
}

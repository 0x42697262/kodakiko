import { useColorScheme } from 'react-native';
import { useSyncExternalStore } from 'react';
import { COLORS, type ColorTokens } from './theme-constants';

export type Theme = { readonly scheme: 'light' | 'dark'; readonly colors: ColorTokens };
export type ThemeChoice = 'system' | 'light' | 'dark';

let choice: ThemeChoice = 'system';
const listeners = new Set<() => void>();

export function setThemeChoice(next: ThemeChoice): void {
  if (next === choice) return;
  choice = next;
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useTheme(): Theme {
  const system = useColorScheme();
  const current = useSyncExternalStore(subscribe, () => choice, () => choice);
  const scheme = current === 'system' ? (system === 'dark' ? 'dark' : 'light') : current;
  return { scheme, colors: COLORS[scheme] };
}

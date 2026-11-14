import { useColorScheme } from 'react-native';
import { useSyncExternalStore } from 'react';
import { COLORS, type ColorTokens } from './theme-constants';

export type Theme = { readonly scheme: 'light' | 'dark'; readonly colors: ColorTokens };
export type ThemeChoice = 'system' | 'light' | 'dark';

let choice: ThemeChoice = 'system';
let pureBlack = false;
let dynamic: { readonly light: ColorTokens; readonly dark: ColorTokens } | null = null;
let stamp = 0;
const listeners = new Set<() => void>();

export function setThemeChoice(next: ThemeChoice, black = pureBlack): void {
  if (next === choice && black === pureBlack) return;
  choice = next;
  pureBlack = black;
  stamp += 1;
  for (const listener of listeners) listener();
}

const PURE_BLACK = Object.freeze({ background: '#000000', surface: '#121212' });

export function setDynamicColors(next: { readonly light: ColorTokens; readonly dark: ColorTokens } | null): void {
  if (next === dynamic) return;
  dynamic = next;
  stamp += 1;
  for (const listener of listeners) listener();
}

export function colorsFor(scheme: 'light' | 'dark', black: boolean): ColorTokens {
  const base = dynamic === null ? COLORS[scheme] : dynamic[scheme];
  return scheme === 'dark' && black ? { ...base, ...PURE_BLACK } : base;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useTheme(): Theme {
  const system = useColorScheme();
  useSyncExternalStore(subscribe, () => stamp, () => stamp);
  const scheme = choice === 'system' ? (system === 'dark' ? 'dark' : 'light') : choice;
  return { scheme, colors: colorsFor(scheme, pureBlack) };
}

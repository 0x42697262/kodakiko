import { getMaterial3Theme, isDynamicThemeSupported } from '@pchmn/expo-material3-theme';
import { colorsFromMaterial3 } from './dynamic-color';
import type { ColorTokens } from './theme-constants';

export const dynamicColorSupported = isDynamicThemeSupported;

let cached: { readonly light: ColorTokens; readonly dark: ColorTokens } | null = null;

export function systemDynamicColors(): { readonly light: ColorTokens; readonly dark: ColorTokens } {
  if (cached === null) {
    const theme = getMaterial3Theme();
    cached = { light: colorsFromMaterial3(theme.light), dark: colorsFromMaterial3(theme.dark) };
  }
  return cached;
}

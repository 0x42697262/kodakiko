import { MD3DarkTheme, MD3LightTheme, type MD3Theme } from 'react-native-paper';
import { COLORS } from './theme-constants';

export const SECONDARY_CONTAINER = Object.freeze({ light: '#DCE3F9', dark: '#2A3354' });

export function paperTheme(scheme: 'light' | 'dark'): MD3Theme {
  const base = scheme === 'dark' ? MD3DarkTheme : MD3LightTheme;
  const colors = COLORS[scheme];
  return {
    ...base,
    colors: {
      ...base.colors,
      primary: colors.primary,
      onPrimary: colors.onPrimary,
      background: colors.background,
      onBackground: colors.text,
      surface: colors.background,
      onSurface: colors.text,
      surfaceVariant: colors.surface,
      onSurfaceVariant: colors.textMuted,
      outline: colors.border,
      error: colors.danger,
      primaryContainer: colors.primary,
      onPrimaryContainer: colors.onPrimary,
      secondaryContainer: SECONDARY_CONTAINER[scheme],
      onSecondaryContainer: colors.text,
      elevation: {
        ...base.colors.elevation,
        level0: 'transparent',
        level1: colors.background,
        level2: colors.surface,
        level3: colors.surface,
      },
    },
  };
}

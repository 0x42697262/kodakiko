import type { ColorTokens } from './theme-constants';

export type Material3Colors = {
  readonly primary: string;
  readonly onPrimary: string;
  readonly background: string;
  readonly onBackground: string;
  readonly surfaceContainerHigh: string;
  readonly onSurfaceVariant: string;
  readonly error: string;
  readonly outlineVariant: string;
};

export function colorsFromMaterial3(scheme: Material3Colors): ColorTokens {
  return {
    background: scheme.background,
    surface: scheme.surfaceContainerHigh,
    text: scheme.onBackground,
    textMuted: scheme.onSurfaceVariant,
    primary: scheme.primary,
    onPrimary: scheme.onPrimary,
    danger: scheme.error,
    border: scheme.outlineVariant,
    focus: scheme.primary,
  };
}

export const COLORS = Object.freeze({
  light: Object.freeze({
    background: '#FFFFFF',
    surface: '#F2F2F7',
    text: '#111114',
    textMuted: '#5C5C66',
    primary: '#2F5FD0',
    onPrimary: '#FFFFFF',
    danger: '#C62828',
    border: '#D1D1D6',
    focus: '#2F5FD0',
  }),
  dark: Object.freeze({
    background: '#0B0B0D',
    surface: '#1C1C1F',
    text: '#F2F2F5',
    textMuted: '#A1A1AA',
    primary: '#7AA2FF',
    onPrimary: '#0B0B0D',
    danger: '#FF6B6B',
    border: '#3A3A3F',
    focus: '#7AA2FF',
  }),
});

export type ColorTokens = Readonly<Record<keyof (typeof COLORS)['light'], string>>;

export const SPACING = Object.freeze({ xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 });
export const RADIUS = Object.freeze({ sm: 8, md: 12 });
export const FONT_SIZE = Object.freeze({
  caption: 13,
  body: 15,
  headline: 17,
  title: 22,
  largeTitle: 28,
});
export const MIN_TOUCH_TARGET = 44;
export const BORDER_WIDTH = 1;
export const DISABLED_OPACITY = 0.5;
export const MULTILINE_MIN_HEIGHT = 120;
export const FONT_FAMILY_MONO = 'monospace';

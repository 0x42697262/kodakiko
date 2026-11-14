import { describe, expect, it, vi } from 'vitest';
import { colorsFromMaterial3 } from '../src/ui/dynamic-color';

vi.mock('react-native', () => ({ useColorScheme: () => 'light' }));

const { colorsFor, setDynamicColors } = await import('../src/ui/theme');

const SCHEME = {
  primary: '#111111',
  onPrimary: '#222222',
  background: '#333333',
  onBackground: '#444444',
  surfaceContainerHigh: '#555555',
  onSurfaceVariant: '#666666',
  error: '#777777',
  outlineVariant: '#888888',
};

describe('dynamic color (spec 24 phase 6)', () => {
  it('maps a Material 3 scheme onto the Kodakiko color set', () => {
    expect(colorsFromMaterial3(SCHEME)).toEqual({
      background: '#333333',
      surface: '#555555',
      text: '#444444',
      textMuted: '#666666',
      primary: '#111111',
      onPrimary: '#222222',
      danger: '#777777',
      border: '#888888',
      focus: '#111111',
    });
  });

  it('uses the dynamic set when present and keeps pure black on top of it', () => {
    const dynamic = colorsFromMaterial3(SCHEME);
    setDynamicColors({ light: dynamic, dark: dynamic });
    expect(colorsFor('light', false).primary).toBe('#111111');
    expect(colorsFor('dark', true).background).toBe('#000000');
    setDynamicColors(null);
    expect(colorsFor('light', false).primary).not.toBe('#111111');
  });
});

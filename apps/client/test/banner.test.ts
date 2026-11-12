import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('react-native', () => ({
  Text: 'Text',
  View: 'View',
  StyleSheet: { create: <T>(styles: T): T => styles },
}));
vi.mock('../src/ui/theme', () => ({
  useTheme: () => ({
    colors: { primary: '#00f', textMuted: '#888', danger: '#f00', surface: '#eee', text: '#000' },
  }),
}));

const { Banner } = await import('../src/ui/Banner');

type Props = { readonly children?: unknown; readonly [key: string]: unknown };

describe('Banner', () => {
  it('puts the testID on the element that holds the message text (Android resource-id + text)', () => {
    const root = Banner({
      testID: 'errors.x',
      kind: 'error',
      message: 'Boom',
    }) as ReactElement<Props>;
    const text = root.props.children as ReactElement<Props>;
    expect(text.type).toBe('Text');
    expect(text.props.children).toBe('Boom');
    expect(text.props['testID']).toBe('errors.x');
  });

  it('keeps the alert role on the error container', () => {
    const root = Banner({
      testID: 'errors.x',
      kind: 'error',
      message: 'Boom',
    }) as ReactElement<Props>;
    expect(root.props['accessibilityRole']).toBe('alert');
  });
});

import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('react-native', () => ({
  Text: 'Text',
  TextInput: 'TextInput',
  View: 'View',
  StyleSheet: { create: <T>(styles: T): T => styles },
}));
vi.mock('../src/ui/theme', () => ({
  useTheme: () => ({
    colors: { text: '#000', textMuted: '#888', surface: '#eee', border: '#ccc', danger: '#f00' },
  }),
}));

const { TextField } = await import('../src/ui/TextField');

type Props = { readonly children?: unknown; readonly [key: string]: unknown };

function inputOf(extra: { readonly selectOnFocus?: boolean }): Props {
  const root = TextField({
    testID: 'x.field',
    label: 'Field',
    value: '',
    onChangeText: () => undefined,
    showLabel: false,
    ...extra,
  }) as ReactElement<Props>;
  const children = root.props.children as readonly unknown[];
  const input = children.find(
    (child) => (child as ReactElement<Props> | null)?.type === 'TextInput',
  ) as ReactElement<Props>;
  return input.props;
}

describe('TextField', () => {
  it('does not select the text on focus by default', () => {
    expect(inputOf({})['selectTextOnFocus']).toBe(false);
  });

  it('selects the whole value on focus when selectOnFocus is set (typing replaces it)', () => {
    expect(inputOf({ selectOnFocus: true })['selectTextOnFocus']).toBe(true);
  });
});

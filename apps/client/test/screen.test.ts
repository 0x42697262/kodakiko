import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('react-native', () => ({
  ScrollView: 'ScrollView',
  View: 'View',
  StyleSheet: { create: <T>(styles: T): T => styles },
}));
vi.mock('react-native-safe-area-context', () => ({ SafeAreaView: 'SafeAreaView' }));
vi.mock('../src/ui/theme', () => ({
  useTheme: () => ({ colors: { background: '#fff' } }),
}));

const { Screen } = await import('../src/ui/Screen');

type Props = { readonly children?: unknown; readonly [key: string]: unknown };

function contentOf(scroll: boolean): ReactElement<Props> {
  const root = Screen({ testID: 'x.screen', scroll, children: null }) as ReactElement<Props>;
  return root.props.children as ReactElement<Props>;
}

describe('Screen', () => {
  it('lets a tap on a button fire while the keyboard is open (scrolling screens)', () => {
    const content = contentOf(true);
    expect(content.type).toBe('ScrollView');
    expect(content.props['keyboardShouldPersistTaps']).toBe('handled');
  });

  it('renders a plain View for non-scrolling screens', () => {
    expect(contentOf(false).type).toBe('View');
  });
});

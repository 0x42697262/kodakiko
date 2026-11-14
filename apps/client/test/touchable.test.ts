import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';

const platform = vi.hoisted(() => ({ OS: 'android' }));
vi.mock('react-native', () => ({ Pressable: 'Pressable', Platform: platform }));
vi.mock('../src/ui/theme', () => ({ useTheme: () => ({ colors: { text: '#111114' } }) }));

const { Touchable, withAlpha } = await import('../src/ui/Touchable');

type Props = { readonly [key: string]: unknown };

function render(props: Props): ReactElement<Props> {
  return Touchable({ testID: 't', onPress: () => undefined, ...props }) as ReactElement<Props>;
}

describe('Touchable (spec 24 phase 2)', () => {
  it('gives Android a foreground ripple in the theme text color at low alpha', () => {
    platform.OS = 'android';
    expect(render({}).props['android_ripple']).toEqual({ color: '#1111141F', borderless: false, foreground: true });
    expect((render({ borderless: true }).props['android_ripple'] as Props)['borderless']).toBe(true);
  });

  it('has no ripple on web and dims while pressed instead', () => {
    platform.OS = 'web';
    const element = render({ style: { padding: 1 } });
    expect(element.props['android_ripple']).toBeUndefined();
    const style = element.props['style'] as (state: { pressed: boolean }) => unknown;
    expect(style({ pressed: false })).toEqual([{ padding: 1 }, null]);
    expect(style({ pressed: true })).toEqual([{ padding: 1 }, { opacity: 0.7 }]);
  });

  it('adds alpha to a hex color', () => {
    expect(withAlpha('#FFFFFF', 0.5)).toBe('#FFFFFF80');
  });
});

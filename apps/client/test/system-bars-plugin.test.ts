import { describe, expect, it } from 'vitest';
import { disableNavigationBarContrast } from '../plugins/with-system-bars.js';

type Style = { $: { name: string; parent?: string }; item?: { $: { name: string }; _: string }[] };

function styles(list: Style[]) {
  return { resources: { style: list } };
}

describe('system bars plugin', () => {
  it('turns off the navigation bar contrast scrim in AppTheme', () => {
    const result = disableNavigationBarContrast(styles([{ $: { name: 'AppTheme', parent: 'Theme.EdgeToEdge' }, item: [] }]));
    expect(result.resources.style[0]?.item).toContainEqual({ $: { name: 'android:enforceNavigationBarContrast' }, _: 'false' });
  });

  it('replaces an existing value instead of adding a duplicate', () => {
    const result = disableNavigationBarContrast(
      styles([{ $: { name: 'AppTheme' }, item: [{ $: { name: 'android:enforceNavigationBarContrast' }, _: 'true' }] }]),
    );
    const items = result.resources.style[0]?.item ?? [];
    expect(items.filter((item: { $: { name: string } }) => item.$.name === 'android:enforceNavigationBarContrast')).toEqual([
      { $: { name: 'android:enforceNavigationBarContrast' }, _: 'false' },
    ]);
  });

  it('leaves other styles alone', () => {
    const result = disableNavigationBarContrast(styles([{ $: { name: 'Other' }, item: [] }]));
    expect(result.resources.style[0]?.item).toEqual([]);
  });
});

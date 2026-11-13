import { describe, expect, it } from 'vitest';
import { searchSettings, SETTINGS_INDEX } from '../src/features/settings/settings-search';

describe('settings search', () => {
  it('finds items across screens, case-insensitively, with the screen to open', () => {
    const results = searchSettings('THEME');
    expect(results.map((result) => result.route)).toContain('/settings/appearance');
    expect(searchSettings('secure screen')[0]?.route).toBe('/settings/security');
    expect(searchSettings('items per row').every((result) => result.route === '/settings/gallery')).toBe(true);
  });

  it('matches every word in any order and ignores blank queries', () => {
    expect(searchSettings('upload large').map((result) => result.route)).toContain('/settings/storage');
    expect(searchSettings('   ')).toEqual([]);
  });

  it('has an entry for every settings screen', () => {
    const routes = new Set(SETTINGS_INDEX.map((entry) => entry.route));
    for (const route of ['/settings/appearance', '/settings/gallery', '/settings/capture', '/settings/security', '/settings/storage', '/settings/sync', '/settings/account', '/settings/about']) {
      expect(routes.has(route)).toBe(true);
    }
  });
});

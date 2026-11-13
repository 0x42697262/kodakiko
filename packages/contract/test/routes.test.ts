import { describe, expect, it } from 'vitest';
import { API_PREFIX, ROUTES, ROUTE_PATTERNS } from '../src/index.ts';

const PLACEHOLDER_PATTERN = /:[A-Za-z]+/g;

describe('routes', () => {
  it('has the same keys in ROUTE_PATTERNS and ROUTES', () => {
    expect(Object.keys(ROUTES).sort()).toEqual(Object.keys(ROUTE_PATTERNS).sort());
  });

  it('builds each pattern from placeholders', () => {
    for (const key of Object.keys(ROUTE_PATTERNS) as (keyof typeof ROUTE_PATTERNS)[]) {
      const builder = ROUTES[key] as (...parts: string[]) => string;
      const placeholders = ROUTE_PATTERNS[key].match(PLACEHOLDER_PATTERN) ?? [];
      expect(placeholders).toHaveLength(builder.length);
      expect(builder(...placeholders)).toBe(ROUTE_PATTERNS[key]);
    }
  });

  it('puts every api route under the prefix and health outside it', () => {
    expect(ROUTE_PATTERNS.config.startsWith(`${API_PREFIX}/`)).toBe(true);
    expect(ROUTE_PATTERNS.healthLive.startsWith(API_PREFIX)).toBe(false);
    expect(ROUTE_PATTERNS.healthReady.startsWith(API_PREFIX)).toBe(false);
  });
});

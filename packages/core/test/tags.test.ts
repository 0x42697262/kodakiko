import { describe, expect, it } from 'vitest';
import { TAG_MAX_CHARS } from '@kodakiko/contract';
import { normalizeTag } from '../src/index.ts';

describe('normalizeTag', () => {
  it('lowercases and trims', () => {
    expect(normalizeTag('  Family ')).toBe('family');
  });

  it('collapses whitespace runs to one space', () => {
    expect(normalizeTag('road \t  trip\n2026')).toBe('road trip 2026');
  });

  it('applies NFKC', () => {
    expect(normalizeTag('ＦＡＭＩＬＹ')).toBe('family');
    expect(normalizeTag('e\u0301')).toBe('\u00e9');
  });

  it('rejects empty input', () => {
    expect(normalizeTag('')).toBeNull();
    expect(normalizeTag('   ')).toBeNull();
  });

  it('rejects input longer than TAG_MAX_CHARS after normalization', () => {
    expect(normalizeTag('a'.repeat(TAG_MAX_CHARS))).toBe('a'.repeat(TAG_MAX_CHARS));
    expect(normalizeTag('a'.repeat(TAG_MAX_CHARS + 1))).toBeNull();
  });

  it('is idempotent', () => {
    const once = normalizeTag('  Mixed   Case ');
    expect(once).not.toBeNull();
    expect(normalizeTag(once ?? '')).toBe(once);
  });
});

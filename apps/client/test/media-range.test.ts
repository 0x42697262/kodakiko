import { describe, expect, it } from 'vitest';
import { streamLayout } from '@kodakiko/core';
import { ciphertextRange, clampRange, parseRange } from '../web/sw/range';

const MIB = 1_048_576;

describe('parseRange', () => {
  const size = 1000;
  it.each([
    [null, { start: 0, end: 999 }],
    ['bytes=0-', { start: 0, end: 999 }],
    ['bytes=10-19', { start: 10, end: 19 }],
    ['bytes=990-5000', { start: 990, end: 999 }],
    ['bytes=-100', { start: 900, end: 999 }],
    ['bytes=-5000', { start: 0, end: 999 }],
    ['bytes=0-1,5-6', { start: 0, end: 1 }],
    ['items=0-5', { start: 0, end: 999 }],
  ])('%s → %o', (header, expected) => {
    expect(parseRange(header, size)).toEqual(expected);
  });

  it.each(['bytes=1000-', 'bytes=1000-1001', 'bytes=-0', 'bytes=20-10'])('%s is unsatisfiable', (header) => {
    expect(parseRange(header, size)).toBeNull();
  });

  it('a 1-byte video has exactly one satisfiable byte', () => {
    expect(parseRange('bytes=0-', 1)).toEqual({ start: 0, end: 0 });
    expect(parseRange('bytes=-1', 1)).toEqual({ start: 0, end: 0 });
    expect(parseRange('bytes=1-', 1)).toBeNull();
  });

  it('an empty video satisfies nothing', () => {
    expect(parseRange(null, 0)).toBeNull();
  });
});

describe('clampRange', () => {
  const layout = streamLayout(10 * MIB + 7, 20);
  it('limits a response to four chunks from the start chunk', () => {
    expect(clampRange({ start: 0, end: 10 * MIB + 6 }, layout, 4)).toEqual({ start: 0, end: 4 * MIB - 1 });
    expect(clampRange({ start: MIB + 5, end: 10 * MIB }, layout, 4)).toEqual({ start: MIB + 5, end: 5 * MIB - 1 });
  });
  it('keeps short ranges untouched', () => {
    expect(clampRange({ start: 3, end: 9 }, layout, 4)).toEqual({ start: 3, end: 9 });
  });
});

describe('ciphertextRange', () => {
  const layout = streamLayout(3 * MIB + 100, 20);
  const step = MIB + 16;
  it('maps chunk indexes to ciphertext bytes after the 24-byte header', () => {
    expect(ciphertextRange(layout, 0, 0)).toEqual({ start: 24, end: 24 + step - 1 });
    expect(ciphertextRange(layout, 1, 2)).toEqual({ start: 24 + step, end: 24 + 3 * step - 1 });
  });
  it('clamps the last partial chunk to the object size', () => {
    expect(ciphertextRange(layout, 3, 3)).toEqual({ start: 24 + 3 * step, end: layout.ciphertextSize - 1 });
  });
  it('handles a 1-byte object', () => {
    const tiny = streamLayout(1, 20);
    expect(ciphertextRange(tiny, 0, 0)).toEqual({ start: 24, end: tiny.ciphertextSize - 1 });
  });
});

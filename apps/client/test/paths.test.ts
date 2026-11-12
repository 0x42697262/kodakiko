import { describe, expect, it } from 'vitest';
import { MalformedPayloadError } from '@kodakiko/core';
import { objectFileName, partialFileName } from '../src/keys/paths';

const VALID_ID = 'AAAAAAAAAAAAAAAAAAAAAA';

describe('object file names', () => {
  it('builds names for valid ids', () => {
    expect(objectFileName(VALID_ID)).toBe(`${VALID_ID}.kdks`);
    expect(partialFileName(VALID_ID)).toBe(`${VALID_ID}.kdks.partial`);
  });

  it('rejects ids that could escape the objects directory or have the wrong shape', () => {
    for (const bad of [
      '',
      '../etc/passwd',
      'a/b',
      'short',
      `${VALID_ID}x`,
      `${VALID_ID.slice(1)}.`,
    ]) {
      expect(() => objectFileName(bad)).toThrow(MalformedPayloadError);
      expect(() => partialFileName(bad)).toThrow(MalformedPayloadError);
    }
  });
});

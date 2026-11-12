import { describe, expect, it } from 'vitest';
import { MIN_OBJECT_BYTES } from '@kodakiko/contract';
import { STREAM_MIN_OBJECT_BYTES } from '../src/index.ts';

describe('contract limits', () => {
  it('keeps MIN_OBJECT_BYTES equal to the smallest STREAM object', () => {
    expect(MIN_OBJECT_BYTES).toBe(STREAM_MIN_OBJECT_BYTES);
  });
});

import { describe, expect, it } from 'vitest';
import {
  bytesSchema,
  decodeBytes,
  encodeBytes,
  idSchema,
  isDecodableBase64Url,
  type Bytes,
} from '../src/index.ts';

function deterministicBytes(length: number): Uint8Array {
  return Uint8Array.from({ length }, (_, index) => (index * 37 + length * 11) % 256);
}

describe('bytes', () => {
  it('encodes with the URL-safe alphabet and no padding', () => {
    expect(encodeBytes(Uint8Array.of(0xfb, 0xff))).toBe('-_8');
  });

  it('round-trips lengths 0..64', () => {
    for (let length = 0; length <= 64; length += 1) {
      const original = deterministicBytes(length);
      expect(decodeBytes(encodeBytes(original))).toEqual(original);
    }
  });

  it('maps the empty array to the empty string and back', () => {
    expect(encodeBytes(new Uint8Array(0))).toBe('');
    expect(decodeBytes('' as Bytes)).toEqual(new Uint8Array(0));
  });

  it('decides decodability', () => {
    expect(isDecodableBase64Url('A')).toBe(false);
    expect(isDecodableBase64Url('')).toBe(true);
  });

  it('throws RangeError for undecodable input', () => {
    expect(() => decodeBytes('A' as Bytes)).toThrow(RangeError);
  });

  it('validates the bytes schema', () => {
    expect(bytesSchema.safeParse('').success).toBe(false);
    expect(bytesSchema.safeParse('A+B').success).toBe(false);
    expect(bytesSchema.safeParse('-_8').success).toBe(true);
  });

  it('validates the id schema', () => {
    expect(idSchema.safeParse('A'.repeat(22)).success).toBe(true);
    expect(idSchema.safeParse('A'.repeat(21)).success).toBe(false);
  });
});

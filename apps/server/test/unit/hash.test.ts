import { describe, expect, it } from 'vitest';
import { constantTimeEqual, hmacSha256, sha256 } from '../../src/platform/hash.ts';

const SHA256_ABC_HEX = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad';
const HMAC_KEY = new TextEncoder().encode('key');
const HMAC_FOX_HEX = 'f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8';

describe('hash helpers', () => {
  it('sha256 matches the known vector', () => {
    expect(sha256(new TextEncoder().encode('abc')).toString('hex')).toBe(SHA256_ABC_HEX);
  });

  it('hmacSha256 concatenates label and message as UTF-8', () => {
    expect(hmacSha256(HMAC_KEY, 'The quick brown fox ', 'jumps over the lazy dog').toString('hex')).toBe(
      HMAC_FOX_HEX,
    );
  });

  it('constantTimeEqual compares content and length', () => {
    expect(constantTimeEqual(Buffer.from('abc'), Buffer.from('abc'))).toBe(true);
    expect(constantTimeEqual(Buffer.from('abc'), Buffer.from('abd'))).toBe(false);
    expect(constantTimeEqual(Buffer.from('abc'), Buffer.from('abcd'))).toBe(false);
  });
});

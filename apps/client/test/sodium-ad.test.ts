import { describe, expect, it } from 'vitest';
import { MalformedPayloadError, utf8Encode } from '@kodakiko/core';
import { additionalDataText } from '../src/adapters/sodium/sodium-ad';

describe('native additional data text', () => {
  it('returns the text of valid UTF-8 bytes, including the empty string and non-ASCII text', () => {
    expect(additionalDataText(new Uint8Array(0))).toBe('');
    expect(additionalDataText(utf8Encode('kodakiko/v1/entry-metadata'))).toBe(
      'kodakiko/v1/entry-metadata',
    );
    expect(additionalDataText(utf8Encode('journal-é-日本'))).toBe('journal-é-日本');
  });

  it('throws MalformedPayloadError for bytes that are not valid UTF-8', () => {
    expect(() => additionalDataText(Uint8Array.of(0xff, 0xfe, 0x00))).toThrow(
      MalformedPayloadError,
    );
    expect(() => additionalDataText(Uint8Array.of(0xc3))).toThrow(MalformedPayloadError);
  });

  it('throws for bytes that decode but would not round-trip', () => {
    expect(() => additionalDataText(Uint8Array.of(0xef, 0xbb, 0xbf, 0x41))).toThrow(
      MalformedPayloadError,
    );
  });
});

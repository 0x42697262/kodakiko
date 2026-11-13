import { describe, expect, it } from 'vitest';
import { thumbnailDataUri } from '../src/features/capture/thumbnail-uri';

describe('thumbnailDataUri', () => {
  it('encodes bytes as a WebP data URI', () => {
    expect(thumbnailDataUri(Uint8Array.of(104, 105))).toBe('data:image/webp;base64,aGk=');
  });

  it('encodes an empty array', () => {
    expect(thumbnailDataUri(new Uint8Array(0))).toBe('data:image/webp;base64,');
  });

  it('encodes arrays longer than one chunk', () => {
    const bytes = Uint8Array.from({ length: 20_000 }, (_, index) => index % 256);
    const uri = thumbnailDataUri(bytes);

    const decoded = Uint8Array.from(atob(uri.slice('data:image/webp;base64,'.length)), (character) =>
      character.charCodeAt(0),
    );
    expect(decoded).toEqual(bytes);
  });
});

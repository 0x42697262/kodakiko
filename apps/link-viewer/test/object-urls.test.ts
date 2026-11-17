import { afterEach, describe, expect, it, vi } from 'vitest';
import { createObjectUrlCache, safeBlobMimeType } from '../src/object-urls.ts';

const BYTES = new Uint8Array([1, 2, 3]);

function capturedTypes(): string[] {
  const types: string[] = [];
  let counter = 0;
  vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => {
    types.push((blob as Blob).type);
    counter += 1;
    return `blob:test/${counter}`;
  });
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
  return types;
}

afterEach(() => vi.restoreAllMocks());

describe('object url mime allowlist', () => {
  it.each(['image/jpeg', 'image/webp', 'video/mp4', 'video/quicktime', 'video/webm'])(
    'keeps the supported type %s',
    (mimeType) => {
      expect(safeBlobMimeType(mimeType)).toBe(mimeType);
      const types = capturedTypes();
      createObjectUrlCache(4).urlFor('k', BYTES, mimeType);
      expect(types).toEqual([mimeType]);
    },
  );

  it.each([
    'text/html',
    'image/svg+xml',
    'application/xhtml+xml',
    'application/javascript',
    'text/html; charset=utf-8',
    'IMAGE/JPEG',
    ' image/jpeg',
    'image/jpeg;x=1',
    '',
    'constructor',
    '__proto__',
  ])('turns the unsupported type %j into application/octet-stream', (mimeType) => {
    expect(safeBlobMimeType(mimeType)).toBe('application/octet-stream');
    const types = capturedTypes();
    createObjectUrlCache(4).urlFor('k', BYTES, mimeType);
    expect(types).toEqual(['application/octet-stream']);
  });
});

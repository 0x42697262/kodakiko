import { BINARY_MIME_TYPE, BLOB_MIME_TYPES } from './constants.ts';

export function safeBlobMimeType(mimeType: string): string {
  return BLOB_MIME_TYPES.has(mimeType) ? mimeType : BINARY_MIME_TYPE;
}

export type ObjectUrlCache = {
  readonly urlFor: (key: string, bytes: Uint8Array, mimeType: string) => string;
  readonly get: (key: string) => string | undefined;
};

export function createObjectUrlCache(capacity: number): ObjectUrlCache {
  const urls = new Map<string, string>();

  function touch(key: string, url: string): void {
    urls.delete(key);
    urls.set(key, url);
  }

  function evict(): void {
    while (urls.size > capacity) {
      const oldest = urls.entries().next();
      if (oldest.done === true) return;
      URL.revokeObjectURL(oldest.value[1]);
      urls.delete(oldest.value[0]);
    }
  }

  return {
    urlFor: (key, bytes, mimeType) => {
      const existing = urls.get(key);
      if (existing !== undefined) {
        touch(key, existing);
        return existing;
      }
      const url = URL.createObjectURL(new Blob([bytes.slice()], { type: safeBlobMimeType(mimeType) }));
      touch(key, url);
      evict();
      return url;
    },
    get: (key) => urls.get(key),
  };
}

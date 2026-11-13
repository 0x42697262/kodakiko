import {
  STREAM_HEADER_BYTES,
  concatBytes,
  createStreamDecryptor,
  wipe,
  type Sodium,
} from '@kodakiko/core';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { ObjectRow, RowStore } from '../../adapters/rowstore/rowstore';
import {
  thumbnailUrls as platformThumbnailUrls,
  type ThumbnailUrls,
} from '../../adapters/thumbnail-urls/thumbnail-urls';
import { THUMBNAIL_MEMORY_CACHE_ITEMS } from '../../constants/gallery-constants';
import type { EntryKeys } from '../keys/entry-keys';

export type ThumbnailRef = { readonly uri: string; readonly release: () => void };

export interface ThumbnailSource {
  readonly get: (entryId: string, mediaId: string) => Promise<ThumbnailRef | null>;
  readonly clear: () => void;
}

export type LocalThumbnailSourceDeps = {
  readonly fileStore: FileStore;
  readonly rowStore: RowStore;
  readonly entryKeys: Pick<EntryKeys, 'variantKey'>;
  readonly sodium: Sodium;
  readonly urls?: ThumbnailUrls;
};

function noop(): void {}

export function decryptWhole(
  sodium: Sodium,
  variantKey: Uint8Array,
  ciphertext: Uint8Array,
  plaintextSize: number,
): Uint8Array {
  const decryptor = createStreamDecryptor(
    sodium,
    variantKey,
    ciphertext.subarray(0, STREAM_HEADER_BYTES),
    plaintextSize,
  );
  const { chunkCount, chunkCiphertextSize } = decryptor.layout;
  const parts: Uint8Array[] = [];
  for (let index = 0; index < chunkCount; index += 1) {
    const start = STREAM_HEADER_BYTES + index * chunkCiphertextSize;
    parts.push(decryptor.decryptNext(ciphertext.subarray(start, start + chunkCiphertextSize)));
  }
  decryptor.finish();
  return concatBytes(...parts);
}

export function createLocalThumbnailSource(deps: LocalThumbnailSourceDeps): ThumbnailSource {
  const { fileStore, rowStore, entryKeys, sodium } = deps;
  const urls = deps.urls ?? platformThumbnailUrls;
  const cache = new Map<string, string>();
  let generation = 0;

  function remember(mediaId: string, uri: string): void {
    cache.set(mediaId, uri);
    while (cache.size > THUMBNAIL_MEMORY_CACHE_ITEMS) {
      const oldest = cache.keys().next();
      if (oldest.done === true) break;
      const evicted = cache.get(oldest.value);
      cache.delete(oldest.value);
      if (evicted !== undefined) urls.revoke(evicted);
    }
  }

  async function presentThumbnail(mediaId: string): Promise<ObjectRow | null> {
    const objects = await rowStore.transaction((tx) => tx.objects.byMedia(mediaId));
    return (
      objects.find((object) => object.variant === 'thumbnail' && object.localState === 'present') ??
      null
    );
  }

  async function get(entryId: string, mediaId: string): Promise<ThumbnailRef | null> {
    const plainUri = fileStore.plainThumbnailUri(mediaId);
    if (plainUri !== null) return { uri: plainUri, release: noop };
    const cached = cache.get(mediaId);
    if (cached !== undefined) {
      cache.delete(mediaId);
      cache.set(mediaId, cached);
      return { uri: cached, release: noop };
    }
    const started = generation;
    const object = await presentThumbnail(mediaId);
    if (object === null) return null;
    const ciphertext = await fileStore.readRange(object.id, 0, object.sizeBytes);
    const variantKey = await entryKeys.variantKey(entryId, mediaId, 'thumbnail');
    let bytes: Uint8Array;
    try {
      bytes = decryptWhole(sodium, variantKey, ciphertext, object.plaintextSize);
    } finally {
      wipe(variantKey);
    }
    if (started !== generation) return null;
    const racing = cache.get(mediaId);
    if (racing !== undefined) return { uri: racing, release: noop };
    const uri = urls.create(bytes);
    remember(mediaId, uri);
    return { uri, release: noop };
  }

  function clear(): void {
    generation += 1;
    for (const uri of cache.values()) urls.revoke(uri);
    cache.clear();
  }

  return { get, clear };
}

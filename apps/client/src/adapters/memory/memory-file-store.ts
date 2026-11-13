import { concatBytes } from '@kodakiko/core';
import { PLAIN_THUMBNAILS_DIRECTORY, plainThumbnailFileName } from '../../keys/paths';
import { EN } from '../../text/en';
import { DownloadHttpError, DownloadSizeMismatchError } from '../filestore/download-errors';
import type { FileStore } from '../filestore/filestore';

const MEMORY_URI_SCHEME = 'memory://';

type StoredObject = { readonly bytes: Uint8Array; readonly modifiedAt: number };
type AccountFiles = {
  readonly objects: Map<string, StoredObject>;
  readonly plainThumbnails: Map<string, StoredObject>;
};

export type MemoryFileStoreOptions = {
  readonly now?: () => number;
  readonly fetchBytes?: (url: string, signal: AbortSignal) => Promise<Uint8Array>;
};

async function fetchWithGlobalFetch(url: string, signal: AbortSignal): Promise<Uint8Array> {
  const response = await fetch(url, { signal });
  if (!response.ok) throw new DownloadHttpError(response.status);
  return new Uint8Array(await response.arrayBuffer());
}

export function createMemoryFileStore(options: MemoryFileStoreOptions = {}): FileStore {
  const now = options.now ?? Date.now;
  const fetchBytes = options.fetchBytes ?? fetchWithGlobalFetch;
  const accounts = new Map<string, AccountFiles>();
  let files: AccountFiles | null = null;

  function current(): AccountFiles {
    if (files === null) throw new Error(EN['filestore.notOpen']);
    return files;
  }

  function stored(objectId: string): Uint8Array {
    const object = current().objects.get(objectId);
    if (object === undefined) throw new Error(EN['filestore.missingObject']);
    return object.bytes;
  }

  return {
    open: async (accountKey) => {
      files = accounts.get(accountKey) ?? { objects: new Map(), plainThumbnails: new Map() };
      accounts.set(accountKey, files);
    },
    writeObject: async (objectId, chunks) => {
      const parts: Uint8Array[] = [];
      for await (const chunk of chunks) parts.push(chunk.slice());
      const bytes = concatBytes(...parts);
      current().objects.set(objectId, { bytes, modifiedAt: now() });
      return bytes.length;
    },
    downloadObject: async (objectId, urlFactory, expectedSize, onProgress, signal) => {
      const bytes = await fetchBytes(await urlFactory(), signal);
      onProgress(bytes.length);
      if (bytes.length !== expectedSize) throw new DownloadSizeMismatchError(expectedSize, bytes.length);
      current().objects.set(objectId, { bytes, modifiedAt: now() });
    },
    readRange: async (objectId, offset, length) => stored(objectId).slice(offset, offset + length),
    size: async (objectId) => current().objects.get(objectId)?.bytes.length ?? null,
    delete: async (objectId) => {
      current().objects.delete(objectId);
    },
    listSizes: async () =>
      new Map([...current().objects.entries()].map(([objectId, object]) => [objectId, object.bytes.length])),
    destroy: async (accountKey) => {
      accounts.delete(accountKey);
      files = null;
    },
    nativeUri: () => null,
    readBlob: async (objectId) => {
      const object = current().objects.get(objectId);
      return object === undefined ? null : new Blob([object.bytes.slice()]);
    },
    writePlainThumbnail: async (mediaId, bytes) => {
      plainThumbnailFileName(mediaId);
      current().plainThumbnails.set(mediaId, { bytes: bytes.slice(), modifiedAt: now() });
    },
    plainThumbnailUri: (mediaId) =>
      current().plainThumbnails.has(mediaId)
        ? `${MEMORY_URI_SCHEME}${PLAIN_THUMBNAILS_DIRECTORY}/${plainThumbnailFileName(mediaId)}`
        : null,
    deletePlainThumbnail: async (mediaId) => {
      current().plainThumbnails.delete(mediaId);
    },
    sweepOrphans: async (knownObjectIds, olderThanMs, knownMediaIds) => {
      const cutoff = now() - olderThanMs;
      for (const [objectId, object] of [...current().objects]) {
        if (!knownObjectIds.has(objectId) && object.modifiedAt < cutoff) {
          current().objects.delete(objectId);
        }
      }
      if (knownMediaIds === undefined) return;
      for (const [mediaId, thumbnail] of [...current().plainThumbnails]) {
        if (!knownMediaIds.has(mediaId) && thumbnail.modifiedAt < cutoff) {
          current().plainThumbnails.delete(mediaId);
        }
      }
    },
  };
}

import { concatBytes } from '@kodakiko/core';
import { EN } from '../../text/en';
import type { FileStore } from '../filestore/filestore';

export function createMemoryFileStore(): FileStore {
  const accounts = new Map<string, Map<string, Uint8Array>>();
  let objects: Map<string, Uint8Array> | null = null;

  function current(): Map<string, Uint8Array> {
    if (objects === null) throw new Error(EN['filestore.notOpen']);
    return objects;
  }

  function stored(objectId: string): Uint8Array {
    const bytes = current().get(objectId);
    if (bytes === undefined) throw new Error(EN['filestore.missingObject']);
    return bytes;
  }

  return {
    open: async (accountKey) => {
      objects = accounts.get(accountKey) ?? new Map<string, Uint8Array>();
      accounts.set(accountKey, objects);
    },
    writeObject: async (objectId, chunks) => {
      const parts: Uint8Array[] = [];
      for await (const chunk of chunks) parts.push(chunk.slice());
      const bytes = concatBytes(...parts);
      current().set(objectId, bytes);
      return bytes.length;
    },
    readRange: async (objectId, offset, length) => stored(objectId).slice(offset, offset + length),
    size: async (objectId) => current().get(objectId)?.length ?? null,
    delete: async (objectId) => {
      current().delete(objectId);
    },
    listSizes: async () =>
      new Map([...current().entries()].map(([objectId, bytes]) => [objectId, bytes.length])),
    destroy: async (accountKey) => {
      accounts.delete(accountKey);
      objects = null;
    },
    nativeUri: () => null,
    readBlob: async (objectId) => {
      const bytes = current().get(objectId);
      return bytes === undefined ? null : new Blob([bytes.slice()]);
    },
  };
}

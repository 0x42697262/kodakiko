import { Directory, File, FileMode, Paths } from 'expo-file-system';
import {
  APP_DATA_DIRECTORY,
  OBJECTS_DIRECTORY,
  PARTIAL_EXTENSION,
  objectFileName,
  objectIdFromFileName,
  partialFileName,
} from '../../keys/paths';
import { EN } from '../../text/en';
import { hashAccountKey } from '../account-key';
import type { FileStore, FileStoreDeps } from './filestore';

function accountDirectory(accountHash: string): Directory {
  return new Directory(Paths.document, APP_DATA_DIRECTORY, accountHash);
}

function deleteIfExists(file: File): void {
  if (file.exists) file.delete();
}

export function createFileStore(deps: FileStoreDeps): FileStore {
  let objects: Directory | null = null;

  function requireObjects(): Directory {
    if (objects === null) throw new Error(EN['filestore.notOpen']);
    return objects;
  }

  function objectFile(objectId: string): File {
    return new File(requireObjects(), objectFileName(objectId));
  }

  async function copyChunks(partial: File, chunks: AsyncIterable<Uint8Array>): Promise<number> {
    partial.create({ overwrite: true });
    const handle = partial.open(FileMode.WriteOnly);
    let total = 0;
    try {
      for await (const chunk of chunks) {
        await handle.writeBytes(chunk);
        total += chunk.length;
      }
    } finally {
      handle.close();
    }
    return total;
  }

  return {
    open: async (accountKey) => {
      const opened = new Directory(
        accountDirectory(hashAccountKey(deps.sodium, accountKey)),
        OBJECTS_DIRECTORY,
      );
      opened.create({ intermediates: true, idempotent: true });
      for (const entry of opened.list()) {
        if (entry instanceof File && entry.name.endsWith(PARTIAL_EXTENSION)) entry.delete();
      }
      objects = opened;
    },
    writeObject: async (objectId, chunks) => {
      const partial = new File(requireObjects(), partialFileName(objectId));
      try {
        const total = await copyChunks(partial, chunks);
        const final = objectFile(objectId);
        deleteIfExists(final);
        partial.rename(objectFileName(objectId));
        return total;
      } catch (error) {
        deleteIfExists(partial);
        throw error;
      }
    },
    readRange: async (objectId, offset, length) => {
      const handle = objectFile(objectId).open(FileMode.ReadOnly);
      try {
        handle.offset = offset;
        return await handle.readBytes(length);
      } finally {
        handle.close();
      }
    },
    size: async (objectId) => {
      const file = objectFile(objectId);
      return file.exists ? file.size : null;
    },
    delete: async (objectId) => {
      deleteIfExists(objectFile(objectId));
    },
    listSizes: async () => {
      const sizes = new Map<string, number>();
      for (const entry of requireObjects().list()) {
        const objectId = entry instanceof File ? objectIdFromFileName(entry.name) : null;
        if (objectId !== null && entry instanceof File) sizes.set(objectId, entry.size);
      }
      return sizes;
    },
    destroy: async (accountKey) => {
      objects = null;
      const directory = accountDirectory(hashAccountKey(deps.sodium, accountKey));
      if (directory.exists) directory.delete();
    },
    nativeUri: (objectId) => objectFile(objectId).uri,
    readBlob: async () => null,
  };
}

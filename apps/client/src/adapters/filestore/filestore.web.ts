import {
  APP_DATA_DIRECTORY,
  OBJECTS_DIRECTORY,
  PARTIAL_EXTENSION,
  objectFileName,
  objectIdFromFileName,
  partialFileName,
} from '../../keys/paths';
import { EN } from '../../text/en';
import { DownloadHttpError, DownloadSizeMismatchError } from './download-errors';
import { hashAccountKey } from '../account-key';
import type { FileStore, FileStoreDeps } from './filestore';

type MovableHandle = FileSystemFileHandle & { readonly move?: (name: string) => Promise<void> };

const NOT_FOUND_ERROR = 'NotFoundError';

function isNotFound(error: unknown): boolean {
  return error instanceof DOMException && error.name === NOT_FOUND_ERROR;
}

async function accountDirectory(
  accountHash: string,
  create: boolean,
): Promise<FileSystemDirectoryHandle> {
  const root = await navigator.storage.getDirectory();
  const app = await root.getDirectoryHandle(APP_DATA_DIRECTORY, { create });
  return app.getDirectoryHandle(accountHash, { create });
}

async function renameFile(
  directory: FileSystemDirectoryHandle,
  handle: FileSystemFileHandle,
  from: string,
  to: string,
): Promise<void> {
  const movable = handle as MovableHandle;
  if (movable.move !== undefined) {
    await movable.move(to);
    return;
  }
  const target = await directory.getFileHandle(to, { create: true });
  const writable = await target.createWritable();
  await writable.write(await handle.getFile());
  await writable.close();
  await directory.removeEntry(from);
}

export function createFileStore(deps: FileStoreDeps): FileStore {
  let objects: FileSystemDirectoryHandle | null = null;

  function requireObjects(): FileSystemDirectoryHandle {
    if (objects === null) throw new Error(EN['filestore.notOpen']);
    return objects;
  }

  async function removeQuietly(name: string): Promise<void> {
    try {
      await requireObjects().removeEntry(name);
    } catch (error) {
      if (!isNotFound(error)) throw error;
    }
  }

  async function findObject(objectId: string): Promise<FileSystemFileHandle | null> {
    try {
      return await requireObjects().getFileHandle(objectFileName(objectId));
    } catch (error) {
      if (isNotFound(error)) return null;
      throw error;
    }
  }

  async function copyChunks(
    handle: FileSystemFileHandle,
    chunks: AsyncIterable<Uint8Array>,
  ): Promise<number> {
    const writable = await handle.createWritable();
    let total = 0;
    try {
      for await (const chunk of chunks) {
        await writable.write(chunk as Uint8Array<ArrayBuffer>);
        total += chunk.length;
      }
      await writable.close();
    } catch (error) {
      await writable.abort().catch(() => undefined);
      throw error;
    }
    return total;
  }

  return {
    open: async (accountKey) => {
      const root = await accountDirectory(hashAccountKey(deps.sodium, accountKey), true);
      const opened = await root.getDirectoryHandle(OBJECTS_DIRECTORY, { create: true });
      for await (const [name] of opened.entries()) {
        if (name.endsWith(PARTIAL_EXTENSION)) await opened.removeEntry(name);
      }
      objects = opened;
      navigator.storage.persist().then(
        (granted) => deps.onPersistResult?.(granted),
        () => undefined,
      );
    },
    writeObject: async (objectId, chunks) => {
      const directory = requireObjects();
      const partialName = partialFileName(objectId);
      const partial = await directory.getFileHandle(partialName, { create: true });
      try {
        const total = await copyChunks(partial, chunks);
        await renameFile(directory, partial, partialName, objectFileName(objectId));
        return total;
      } catch (error) {
        await removeQuietly(partialName);
        throw error;
      }
    },
    downloadObject: async (objectId, urlFactory, expectedSize, onProgress, signal) => {
      const directory = requireObjects();
      const partialName = partialFileName(objectId);
      await removeQuietly(partialName);
      const partial = await directory.getFileHandle(partialName, { create: true });
      try {
        const response = await fetch(await urlFactory(), { signal });
        if (!response.ok || response.body === null) throw new DownloadHttpError(response.status);
        const writable = await partial.createWritable();
        let received = 0;
        try {
          const reader = response.body.getReader();
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            await writable.write(value);
            received += value.length;
            onProgress(received);
          }
          await writable.close();
        } catch (error) {
          await writable.abort();
          throw error;
        }
        if (received !== expectedSize) throw new DownloadSizeMismatchError(expectedSize, received);
        await renameFile(directory, partial, partialName, objectFileName(objectId));
      } catch (error) {
        await removeQuietly(partialName);
        throw error;
      }
    },
    readRange: async (objectId, offset, length) => {
      const handle = await findObject(objectId);
      if (handle === null) throw new Error(EN['filestore.missingObject']);
      const file = await handle.getFile();
      return new Uint8Array(await file.slice(offset, offset + length).arrayBuffer());
    },
    size: async (objectId) => (await (await findObject(objectId))?.getFile())?.size ?? null,
    delete: (objectId) => removeQuietly(objectFileName(objectId)),
    listSizes: async () => {
      const sizes = new Map<string, number>();
      for await (const [name, handle] of requireObjects().entries()) {
        const objectId = objectIdFromFileName(name);
        if (objectId !== null && handle.kind === 'file') {
          sizes.set(objectId, (await (handle as FileSystemFileHandle).getFile()).size);
        }
      }
      return sizes;
    },
    destroy: async (accountKey) => {
      objects = null;
      const hash = hashAccountKey(deps.sodium, accountKey);
      try {
        const root = await navigator.storage.getDirectory();
        const app = await root.getDirectoryHandle(APP_DATA_DIRECTORY);
        await app.removeEntry(hash, { recursive: true });
      } catch (error) {
        if (!isNotFound(error)) throw error;
      }
    },
    nativeUri: () => null,
    readBlob: async (objectId) => (await (await findObject(objectId))?.getFile()) ?? null,
    writePlainThumbnail: async () => undefined,
    plainThumbnailUri: () => null,
    deletePlainThumbnail: async () => undefined,
    sweepOrphans: async (knownObjectIds, olderThanMs) => {
      const cutoff = Date.now() - olderThanMs;
      const stale: string[] = [];
      for await (const [name, handle] of requireObjects().entries()) {
        const objectId = objectIdFromFileName(name);
        if (objectId === null || handle.kind !== 'file' || knownObjectIds.has(objectId)) continue;
        const file = await (handle as FileSystemFileHandle).getFile();
        if (file.lastModified < cutoff) stale.push(name);
      }
      for (const name of stale) await removeQuietly(name);
    },
  };
}

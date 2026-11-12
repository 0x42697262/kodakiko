import type { Sodium } from '@kodakiko/core';

export interface FileStore {
  readonly open: (accountKey: string) => Promise<void>;
  readonly writeObject: (objectId: string, chunks: AsyncIterable<Uint8Array>) => Promise<number>;
  readonly readRange: (objectId: string, offset: number, length: number) => Promise<Uint8Array>;
  readonly size: (objectId: string) => Promise<number | null>;
  readonly delete: (objectId: string) => Promise<void>;
  readonly listSizes: () => Promise<ReadonlyMap<string, number>>;
  readonly destroy: (accountKey: string) => Promise<void>;
  readonly nativeUri: (objectId: string) => string | null;
  readonly readBlob: (objectId: string) => Promise<Blob | null>;
}

export type FileStoreDeps = {
  readonly sodium: Sodium;
  readonly onPersistResult?: (granted: boolean) => void;
};

export declare function createFileStore(deps: FileStoreDeps): FileStore;

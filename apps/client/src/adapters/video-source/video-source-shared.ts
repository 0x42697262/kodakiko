import type { Sodium } from '@kodakiko/core';
import type { EntryMetadataV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import type { FileStore } from '../filestore/filestore';
import type { HttpClient } from '../http/http';
import type { RowStore } from '../rowstore/rowstore';
import type { TempFiles } from '../temp-files/temp-files';
import type { Downloader } from '../../services/downloads/downloader';
import type { EntryKeys } from '../../services/keys/entry-keys';

export class StreamingUnavailableError extends Error {
  constructor() {
    super('Streaming is not available in this browser.');
  }
}

export type VideoSourceDeps = {
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly entryKeys: Pick<EntryKeys, 'variantKey'>;
  readonly sodium: Sodium;
  readonly http: HttpClient;
  readonly readMetadata: (entryId: string) => Promise<z.infer<typeof EntryMetadataV1>>;
  readonly downloader: Downloader;
  readonly tempFiles: TempFiles;
  readonly randomId: () => string;
};

export function fetchOriginalWith(downloader: Downloader) {
  return async (objectId: string, onProgress: (fraction: number) => void): Promise<void> => {
    const stop = downloader.onProgress(objectId, (received, total) => onProgress(total > 0 ? received / total : 0));
    try {
      await downloader.fetchObject(objectId, 'opened');
    } finally {
      stop();
    }
  };
}

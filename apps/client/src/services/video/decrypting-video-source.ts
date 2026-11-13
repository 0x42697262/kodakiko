import { createStreamDecryptor, STREAM_HEADER_BYTES, wipe, type Sodium } from '@kodakiko/core';
import type { EntryMetadataV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import { MIME_EXTENSIONS, VIEW_FALLBACK_EXTENSION } from '../../keys/mime-extensions';
import type { EntryKeys } from '../keys/entry-keys';
import type { PreparedVideo, VideoSource, VideoPrepareInput } from './video-source';

type Metadata = z.infer<typeof EntryMetadataV1>;
const HALF = 0.5;
const DONE = 1;

export class NotEnoughSpaceError extends Error {
  constructor() {
    super('Not enough free space.');
  }
}

export class VideoMissingError extends Error {
  constructor() {
    super('The video is not available.');
  }
}

export type VideoFile = {
  readonly uri: string;
  readonly write: (bytes: Uint8Array) => Promise<void>;
  readonly close: () => Promise<void>;
  readonly delete: () => Promise<void>;
};

export type DecryptingVideoSourceDeps = {
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly entryKeys: Pick<EntryKeys, 'variantKey'>;
  readonly sodium: Sodium;
  readonly readMetadata: (entryId: string) => Promise<Metadata>;
  readonly fetchOriginal: (objectId: string, onProgress: (fraction: number) => void) => Promise<void>;
  readonly createFile: (extension: string, mimeType: string) => Promise<VideoFile>;
  readonly freeSpaceBytes: () => Promise<number>;
  readonly marginBytes: number;
  readonly releaseFiles?: () => Promise<void>;
};

function abortIfNeeded(signal: AbortSignal): void {
  if (signal.aborted) throw signal.reason instanceof Error ? signal.reason : new Error('aborted');
}

export function createDecryptingVideoSource(deps: DecryptingVideoSourceDeps): VideoSource {
  const open = new Set<PreparedVideo>();

  async function prepare({ entryId, mediaId, onProgress, signal }: VideoPrepareInput): Promise<PreparedVideo> {
    const rows = await deps.rowStore.transaction((tx) => tx.objects.byMedia(mediaId));
    const row = rows.find((candidate) => candidate.variant === 'original');
    const descriptor = (await deps.readMetadata(entryId)).media.find((item) => item.mediaId === mediaId);
    if (row === undefined || descriptor === undefined) throw new VideoMissingError();
    if (row.localState !== 'present') {
      await deps.fetchOriginal(row.id, (fraction) => onProgress(fraction * HALF));
    }
    abortIfNeeded(signal);
    if ((await deps.freeSpaceBytes()) <= row.plaintextSize + deps.marginBytes) throw new NotEnoughSpaceError();
    const file = await deps.createFile(MIME_EXTENSIONS[descriptor.mimeType] ?? VIEW_FALLBACK_EXTENSION, descriptor.mimeType);
    const key = await deps.entryKeys.variantKey(entryId, mediaId, 'original');
    try {
      const header = await deps.fileStore.readRange(row.id, 0, STREAM_HEADER_BYTES);
      const decryptor = createStreamDecryptor(deps.sodium, key, header, row.plaintextSize);
      const step = decryptor.layout.chunkCiphertextSize;
      const total = row.sizeBytes - STREAM_HEADER_BYTES;
      for (let offset = STREAM_HEADER_BYTES; offset < row.sizeBytes; offset += step) {
        abortIfNeeded(signal);
        const chunk = await deps.fileStore.readRange(row.id, offset, Math.min(step, row.sizeBytes - offset));
        await file.write(decryptor.decryptNext(chunk));
        onProgress(HALF + ((offset + chunk.length - STREAM_HEADER_BYTES) / total) * HALF);
      }
      decryptor.finish();
      await file.close();
    } catch (error) {
      await file.close().catch(() => undefined);
      await file.delete();
      throw error;
    } finally {
      wipe(key);
    }
    onProgress(DONE);
    let released = false;
    const prepared: PreparedVideo = {
      uri: file.uri,
      release: async () => {
        if (released) return;
        released = true;
        open.delete(prepared);
        await file.delete();
      },
    };
    open.add(prepared);
    return prepared;
  }

  return {
    prepare,
    releaseAll: async () => {
      for (const prepared of [...open]) await prepared.release();
      await deps.releaseFiles?.();
    },
  };
}

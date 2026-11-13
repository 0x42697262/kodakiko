import {
  createStreamDecryptor,
  STREAM_HEADER_BYTES,
  wipe,
  type Sodium,
} from '@kodakiko/core';
import type { EntryMetadataV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import type { Clock } from '../../adapters/clock';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { PlaintextSink } from '../../adapters/plaintext-sink/plaintext-sink';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import type { EntryKeys } from '../keys/entry-keys';

type Metadata = z.infer<typeof EntryMetadataV1>;

export type OriginalRef = { readonly uri: string; readonly mimeType: string; readonly release: () => Promise<void> };

export interface OriginalSource {
  readonly open: (entryId: string, mediaId: string) => Promise<OriginalRef | 'missing'>;
  readonly clearAll: () => Promise<void>;
}

export type LocalOriginalSourceDeps = {
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly entryKeys: Pick<EntryKeys, 'variantKey'>;
  readonly sodium: Sodium;
  readonly clock: Clock;
  readonly readMetadata: (entryId: string) => Promise<Metadata>;
  readonly sink: PlaintextSink;
};

export function createLocalOriginalSource(deps: LocalOriginalSourceDeps): OriginalSource {
  const open = new Set<OriginalRef>();

  async function decryptInto(
    objectId: string,
    sizeBytes: number,
    plaintextSize: number,
    key: Uint8Array,
    mimeType: string,
  ) {
    const header = await deps.fileStore.readRange(objectId, 0, STREAM_HEADER_BYTES);
    const decryptor = createStreamDecryptor(deps.sodium, key, header, plaintextSize);
    const writer = await deps.sink.begin(mimeType);
    try {
      const step = decryptor.layout.chunkCiphertextSize;
      for (let offset = STREAM_HEADER_BYTES; offset < sizeBytes; offset += step) {
        const chunk = await deps.fileStore.readRange(objectId, offset, Math.min(step, sizeBytes - offset));
        await writer.write(decryptor.decryptNext(chunk));
      }
      decryptor.finish();
      return await writer.finish();
    } catch (error) {
      await writer.abort();
      throw error;
    }
  }

  return {
    open: async (entryId, mediaId) => {
      const rows = await deps.rowStore.transaction((tx) => tx.objects.byMedia(mediaId));
      const row = rows.find((candidate) => candidate.variant === 'original');
      if (row === undefined || row.localState !== 'present') return 'missing';
      const metadata = await deps.readMetadata(entryId);
      const descriptor = metadata.media.find((item) => item.mediaId === mediaId);
      if (descriptor === undefined) return 'missing';
      const key = await deps.entryKeys.variantKey(entryId, mediaId, 'original');
      let plain;
      try {
        plain = await decryptInto(row.id, row.sizeBytes, row.plaintextSize, key, descriptor.mimeType);
      } finally {
        wipe(key);
      }
      await deps.rowStore.transaction(async (tx) => {
        const current = await tx.objects.get(row.id);
        if (current !== null) await tx.objects.put({ ...current, lastAccessAt: deps.clock.now() });
      });
      let released = false;
      const ref: OriginalRef = {
        uri: plain.uri,
        mimeType: descriptor.mimeType,
        release: async () => {
          if (released) return;
          released = true;
          open.delete(ref);
          await plain.release();
        },
      };
      open.add(ref);
      return ref;
    },
    clearAll: async () => {
      const refs = [...open];
      for (const ref of refs) await ref.release();
    },
  };
}

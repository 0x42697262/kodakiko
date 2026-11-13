import { beforeEach, describe, expect, it } from 'vitest';
import type { Sodium } from '@kodakiko/core';
import { createNodeSodium } from '../../../packages/core/test/sodium-node';
import type { PlaintextSink } from '../src/adapters/plaintext-sink/plaintext-sink';
import { createLocalOriginalSource, type OriginalSource } from '../src/services/originals/original-source';
import { createEntriesHarness, fakeJpeg, type EntriesHarness } from './support/entries-harness';

type FakeSink = PlaintextSink & {
  readonly written: Map<string, Uint8Array>;
  readonly released: string[];
};

function createFakeSink(): FakeSink {
  const written = new Map<string, Uint8Array>();
  const released: string[] = [];
  let next = 0;
  return {
    written,
    released,
    begin: async (mimeType) => {
      const parts: Uint8Array[] = [];
      return {
        write: async (bytes) => {
          parts.push(bytes.slice());
        },
        finish: async () => {
          next += 1;
          const uri = `fake:${mimeType}:${next}`;
          const total = parts.reduce((sum, part) => sum + part.length, 0);
          const joined = new Uint8Array(total);
          let offset = 0;
          for (const part of parts) {
            joined.set(part, offset);
            offset += part.length;
          }
          written.set(uri, joined);
          return {
            uri,
            release: async () => {
              released.push(uri);
            },
          };
        },
        abort: async () => undefined,
      };
    },
  };
}

let sodium: Sodium;
let harness: EntriesHarness;
let sink: FakeSink;
let source: OriginalSource;

beforeEach(async () => {
  sodium = await createNodeSodium();
  harness = await createEntriesHarness(sodium);
  sink = createFakeSink();
  source = createLocalOriginalSource({
    rowStore: harness.fixture.rowStore,
    fileStore: harness.fixture.fileStore,
    entryKeys: harness.entryKeys,
    sodium,
    clock: harness.clock,
    readMetadata: harness.service.readMetadata,
    sink,
  });
});

async function firstMediaId(entryId: string): Promise<string> {
  const metadata = await harness.service.readMetadata(entryId);
  return metadata.media[0]?.mediaId ?? '';
}

describe('local original source', () => {
  it('decrypts a stored original to its exact bytes with its MIME type', async () => {
    const photo = fakeJpeg(1_500_000, 9);
    const entryId = await harness.createEntry([photo]);
    const mediaId = await firstMediaId(entryId);

    const ref = await source.open(entryId, mediaId);

    expect(ref).not.toBe('missing');
    if (ref === 'missing') return;
    expect(ref.mimeType).toBe('image/jpeg');
    expect(sink.written.get(ref.uri)).toEqual(photo);
  });

  it('updates the original object row last access time', async () => {
    const entryId = await harness.createEntry([fakeJpeg(1000)]);
    const mediaId = await firstMediaId(entryId);
    harness.clock.advance(5_000);
    await source.open(entryId, mediaId);
    const rows = await harness.fixture.rowStore.transaction((tx) => tx.objects.byMedia(mediaId));
    const original = rows.find((row) => row.variant === 'original');
    expect(original?.lastAccessAt).toBe(harness.clock.now());
  });

  it('releases a ref once and clearAll releases every open ref', async () => {
    const entryId = await harness.createEntry([fakeJpeg(1000, 1), fakeJpeg(1000, 2)]);
    const metadata = await harness.service.readMetadata(entryId);
    const [first, second] = metadata.media;
    const one = await source.open(entryId, first?.mediaId ?? '');
    const two = await source.open(entryId, second?.mediaId ?? '');
    if (one === 'missing' || two === 'missing') throw new Error('expected refs');

    await one.release();
    await one.release();
    expect(sink.released).toEqual([one.uri]);

    await source.clearAll();
    expect(sink.released).toEqual([one.uri, two.uri]);
  });

  it('returns missing when the original is not on the device', async () => {
    const entryId = await harness.createEntry([fakeJpeg(1000)]);
    const mediaId = await firstMediaId(entryId);
    const rows = await harness.fixture.rowStore.transaction((tx) => tx.objects.byMedia(mediaId));
    const original = rows.find((row) => row.variant === 'original');
    if (original === undefined) throw new Error('expected an original row');
    await harness.fixture.rowStore.transaction((tx) =>
      tx.objects.put({ ...original, localState: 'missing' }),
    );
    expect(await source.open(entryId, mediaId)).toBe('missing');
  });
});

import { resolveObjectURL } from 'node:buffer';
import { beforeEach, describe, expect, it } from 'vitest';
import { createStreamEncryptor, type Sodium } from '@kodakiko/core';
import { thumbnailUrls as nativeUrls } from '../src/adapters/thumbnail-urls/thumbnail-urls.native';
import { thumbnailUrls as webUrls } from '../src/adapters/thumbnail-urls/thumbnail-urls.web';
import { THUMBNAIL_MEMORY_CACHE_ITEMS } from '../src/constants/gallery-constants';
import { createEntryKeys, type EntryKeys } from '../src/services/keys/entry-keys';
import { createLocalThumbnailSource } from '../src/services/thumbnails/thumbnail-source';
import {
  createAccountFixture,
  createLocalEntry,
  createMemoryFileStore,
  createMemoryRowStore,
  createNodeSodium,
  type AccountFixture,
  type LocalEntry,
} from './support';

const THUMBNAIL_BYTES = 64;
const DATA_URI_PREFIX = 'data:image/webp;base64,';

let sodium: Sodium;
let fixture: AccountFixture;
let entryKeys: EntryKeys;

async function* chunksOf(variantKey: Uint8Array, plaintext: Uint8Array): AsyncGenerator<Uint8Array> {
  const encryptor = createStreamEncryptor(sodium, variantKey, plaintext.length);
  yield encryptor.header;
  const { chunkSize, chunkCount } = encryptor.layout;
  for (let index = 0; index < chunkCount; index += 1) {
    yield encryptor.encryptNext(plaintext.slice(index * chunkSize, (index + 1) * chunkSize));
  }
}

async function entryWithThumbnail(fill: number): Promise<{ entry: LocalEntry; thumbnail: Uint8Array }> {
  const entry = await createLocalEntry(fixture, {
    mediaBytes: [new Uint8Array(16).fill(fill)],
    note: '',
    tags: [],
    enqueue: false,
  });
  const mediaId = entry.mediaIds[0] ?? '';
  const thumbnail = new Uint8Array(THUMBNAIL_BYTES).fill(fill % 251);
  thumbnail[0] = fill & 0xff;
  thumbnail[1] = fill >> 8;
  const key = await entryKeys.variantKey(entry.entryId, mediaId, 'thumbnail');
  await fixture.fileStore.writeObject(entry.thumbnailObjectIds[0] ?? '', chunksOf(key, thumbnail));
  return { entry, thumbnail };
}

function dataUriBytes(uri: string): Uint8Array {
  expect(uri.startsWith(DATA_URI_PREFIX)).toBe(true);
  return Uint8Array.from(atob(uri.slice(DATA_URI_PREFIX.length)), (character) =>
    character.charCodeAt(0),
  );
}

async function blobUriBytes(uri: string): Promise<Uint8Array> {
  const blob = resolveObjectURL(uri);
  if (blob === undefined) throw new Error('object URL was revoked');
  expect(blob.type).toBe('image/webp');
  return new Uint8Array(await blob.arrayBuffer());
}

beforeEach(async () => {
  sodium ??= await createNodeSodium();
  fixture = await createAccountFixture(sodium, createMemoryRowStore(), createMemoryFileStore());
  entryKeys = createEntryKeys({ sodium, rowStore: fixture.rowStore, account: () => fixture.keys });
});

function source(urls: typeof webUrls) {
  return createLocalThumbnailSource({
    fileStore: fixture.fileStore,
    rowStore: fixture.rowStore,
    entryKeys,
    sodium,
    urls,
  });
}

describe('local thumbnail source', () => {
  it('returns the plain thumbnail file when one exists (Android plain mode)', async () => {
    const { entry } = await entryWithThumbnail(1);
    const mediaId = entry.mediaIds[0] ?? '';
    await fixture.fileStore.writePlainThumbnail(mediaId, Uint8Array.of(1, 2, 3));
    const ref = await source(nativeUrls).get(entry.entryId, mediaId);
    expect(ref?.uri).toBe(fixture.fileStore.plainThumbnailUri(mediaId));
    ref?.release();
  });

  it('decrypts a present encrypted thumbnail into a data URI (Android encrypted mode)', async () => {
    const { entry, thumbnail } = await entryWithThumbnail(2);
    const ref = await source(nativeUrls).get(entry.entryId, entry.mediaIds[0] ?? '');
    expect(dataUriBytes(ref?.uri ?? '')).toEqual(thumbnail);
  });

  it('decrypts a present encrypted thumbnail into an image/webp object URL (web)', async () => {
    const { entry, thumbnail } = await entryWithThumbnail(3);
    const ref = await source(webUrls).get(entry.entryId, entry.mediaIds[0] ?? '');
    expect(await blobUriBytes(ref?.uri ?? '')).toEqual(thumbnail);
  });

  it('returns null when the thumbnail object is not present locally', async () => {
    const { entry } = await entryWithThumbnail(4);
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.objects.get(entry.thumbnailObjectIds[0] ?? '');
      if (row !== null) await tx.objects.put({ ...row, localState: 'missing' });
    });
    expect(await source(webUrls).get(entry.entryId, entry.mediaIds[0] ?? '')).toBeNull();
  });

  it('returns null for an unknown media id', async () => {
    const { entry } = await entryWithThumbnail(5);
    expect(await source(webUrls).get(entry.entryId, 'unknown-media')).toBeNull();
  });

  it('serves a repeated request from memory with the same URI', async () => {
    const { entry } = await entryWithThumbnail(6);
    const thumbnails = source(webUrls);
    const first = await thumbnails.get(entry.entryId, entry.mediaIds[0] ?? '');
    const second = await thumbnails.get(entry.entryId, entry.mediaIds[0] ?? '');
    expect(second?.uri).toBe(first?.uri);
  });

  it(`keeps ${THUMBNAIL_MEMORY_CACHE_ITEMS} items: the 301st evicts the least recently used and revokes its URL`, async () => {
    expect(THUMBNAIL_MEMORY_CACHE_ITEMS).toBe(300);
    const thumbnails = source(webUrls);
    const uris: string[] = [];
    const created: { entry: LocalEntry; thumbnail: Uint8Array }[] = [];
    for (let position = 0; position <= THUMBNAIL_MEMORY_CACHE_ITEMS; position += 1) {
      created.push(await entryWithThumbnail(position + 10));
    }
    for (const [position, item] of created.entries()) {
      const ref = await thumbnails.get(item.entry.entryId, item.entry.mediaIds[0] ?? '');
      uris.push(ref?.uri ?? '');
      if (position === THUMBNAIL_MEMORY_CACHE_ITEMS - 1) {
        expect(resolveObjectURL(uris[0] ?? '')).toBeDefined();
      }
    }
    expect(resolveObjectURL(uris[0] ?? '')).toBeUndefined();
    expect(await blobUriBytes(uris[1] ?? '')).toEqual(created[1]?.thumbnail);
    expect(await blobUriBytes(uris[THUMBNAIL_MEMORY_CACHE_ITEMS] ?? '')).toEqual(
      created[THUMBNAIL_MEMORY_CACHE_ITEMS]?.thumbnail,
    );

    const again = await thumbnails.get(created[0]?.entry.entryId ?? '', created[0]?.entry.mediaIds[0] ?? '');
    expect(again?.uri).not.toBe(uris[0]);
    expect(resolveObjectURL(uris[1] ?? '')).toBeUndefined();

    thumbnails.clear();
    expect(resolveObjectURL(again?.uri ?? '')).toBeUndefined();
    expect(resolveObjectURL(uris[THUMBNAIL_MEMORY_CACHE_ITEMS] ?? '')).toBeUndefined();
  });
});

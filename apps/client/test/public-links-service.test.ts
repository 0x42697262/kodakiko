import { beforeEach, describe, expect, it } from 'vitest';
import {
  AD_CONTEXTS,
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  STREAM_HEADER_BYTES,
  byteSourceFromBytes,
  createStreamDecryptor,
  openJson,
  unwrapKey,
  type Sodium,
} from '@kodakiko/core';
import {
  CollectionMetadataV1,
  EntryMetadataV1,
  ROUTES,
  decodeBytes,
  encodeBytes,
  type MediaDescriptorV1 as MediaDescriptorV1Type,
} from '@kodakiko/contract';
import type { z } from 'zod';
import { createNodeSodium } from '../../../packages/core/test/sodium-node';
import { ftyp, box, concat, moov as moovBox, mvhd, udta, xyzBox, seededBytes } from '../../../packages/core/test/media/builders';
import { createDecryptingByteSource } from '../src/services/links/decrypting-source';
import { buildLinkUrl, parseLinkFragment } from '../src/services/links/link-url';
import { createLinkStripper, StripFailedError, type LinkStripper } from '../src/services/links/link-stripper';
import { createPublicLinksService, type PublicLinksService } from '../src/services/links/public-links-service';
import { createFakeHttp, type FakeHttp } from './support/fake-http';
import { createFakeUploader, type FakeUploader } from './support/fake-uploader';
import { createEntriesHarness, fakeJpeg, type EntriesHarness } from './support/entries-harness';

type MediaDescriptor = z.infer<typeof MediaDescriptorV1Type>;

const BASE_URL = 'https://journal.example';
const NOW = '2026-06-01T10:00:00.000Z';
const LINK_ID = 'L'.repeat(22);
const KEY_BYTES = 32;

let sodium: Sodium;
let harness: EntriesHarness;
let fake: FakeHttp;
let uploader: FakeUploader;
let uploaded: Map<string, Uint8Array>;
let service: PublicLinksService;
let stripper: LinkStripper;

function mp4WithLocation(): Uint8Array {
  const payload = seededBytes(2048, 5);
  const head = ftyp('isom', ['isom']);
  const build = (offset: number) =>
    moovBox(
      mvhd({ version: 0, creationSeconds1904: 3_800_000_000 }),
      udta(xyzBox('+48.8584+002.2945/')),
      box('trak', box('mdia', box('minf', box('stbl', box('stco', concat([0, 0, 0, 0], [0, 0, 0, 1], [(offset >>> 24) & 255, (offset >>> 16) & 255, (offset >>> 8) & 255, offset & 255])))))),
    );
  const provisional = build(0);
  const offset = head.length + provisional.length + 8;
  return concat(head, build(offset), box('mdat', payload));
}

function passThroughReencoder() {
  return async (bytes: Uint8Array) => ({ bytes: bytes.slice(), width: 100, height: 100 });
}

function serverRoutes(): void {
  fake.on('POST', ROUTES.collections(), (request) => {
    const body = request.body as { id: string; keyByOwner: string; metadataBlob: string };
    return {
      id: body.id,
      kind: 'link',
      role: 'owner',
      ownerId: 'O'.repeat(22),
      ownerUsername: 'ada',
      keyMaterial: body.keyByOwner,
      metadataBlob: body.metadataBlob,
      lastSeq: 0,
      createdAt: NOW,
      metaUpdatedAt: NOW,
      recipientCount: null,
    };
  });
  fake.on('POST', ROUTES.entries(), (request) => {
    const body = request.body as { id: string; metadataBlob: string };
    return {
      id: body.id,
      ownerId: 'O'.repeat(22),
      purpose: 'link_copy',
      revision: 1,
      metadataBlob: body.metadataBlob,
      trashedAt: null,
      createdAt: NOW,
      updatedAt: NOW,
    };
  });
  fake.on('POST', ROUTES.objectsPresign(), (request) => {
    const body = request.body as { id: string };
    return { mode: 'single', url: `https://bucket.example/${body.id}`, expiresAt: NOW };
  });
  fake.on('POST', /\/objects\/[^/]+\/confirm$/, (request) => {
    const id = request.path.split('/').at(-2) ?? '';
    return { id, mediaId: 'M'.repeat(22), variant: 'original', sizeBytes: uploaded.get(id)?.length ?? 1, state: 'uploaded' };
  });
  fake.on('POST', ROUTES.publicLinks(), (request) => {
    const body = request.body as { collectionId: string };
    return { linkId: LINK_ID, collectionId: body.collectionId, expiresAt: null, maxViews: null, viewCount: 0, createdAt: NOW };
  });
  fake.on('DELETE', /\/collections\/[^/]+$/, () => undefined);
}

function createService(custom?: LinkStripper): PublicLinksService {
  return createPublicLinksService({
    http: fake.http,
    sodium,
    account: () => harness.fixture.keys,
    readMetadata: (entryId) => harness.service.readMetadata(entryId),
    entryKeys: harness.entryKeys,
    rowStore: harness.fixture.rowStore,
    fileStore: harness.fixture.fileStore,
    fetchObject: async () => undefined,
    stripper: custom ?? stripper,
    uploader,
    baseUrl: async () => BASE_URL,
    randomId: harness.fixture.randomId,
    clock: harness.clock,
  });
}

beforeEach(async () => {
  sodium = await createNodeSodium();
  harness = await createEntriesHarness(sodium);
  fake = createFakeHttp();
  uploaded = new Map();
  uploader = createFakeUploader();
  const enqueue = uploader.enqueue;
  (uploader as { enqueue: FakeUploader['enqueue'] }).enqueue = async (jobs) => {
    await enqueue(jobs);
    for (const job of jobs) {
      const size = (await harness.fixture.fileStore.size(job.objectId)) ?? 0;
      uploaded.set(job.objectId, await harness.fixture.fileStore.readRange(job.objectId, 0, size));
      setTimeout(() => uploader.emit({ type: 'completed', objectId: job.objectId }), 0);
    }
  };
  stripper = createLinkStripper({ reencodePhoto: passThroughReencoder() });
  serverRoutes();
  service = createService();
});

function requestOf(method: string, pattern: string | RegExp) {
  return fake.requests.filter((request) => request.method === method && (typeof pattern === 'string' ? request.path === pattern : pattern.test(request.path)));
}

function decryptUploaded(objectId: string, key: Uint8Array, plaintextSize: number): Uint8Array {
  const ciphertext = uploaded.get(objectId);
  if (ciphertext === undefined) throw new Error(`not uploaded ${objectId}`);
  const decryptor = createStreamDecryptor(sodium, key, ciphertext.subarray(0, STREAM_HEADER_BYTES), plaintextSize);
  const parts: Uint8Array[] = [];
  const step = decryptor.layout.chunkCiphertextSize;
  for (let offset = STREAM_HEADER_BYTES; offset < ciphertext.length; offset += step) parts.push(decryptor.decryptNext(ciphertext.subarray(offset, offset + step)));
  decryptor.finish();
  return concat(...parts);
}

async function syncedEntry(media: readonly Uint8Array[], note = 'secret picnic', tags = ['family']): Promise<string> {
  const entryId = await harness.createEntry(media, { note, tags });
  await harness.markSynced(entryId);
  return entryId;
}

describe('public links service', () => {
  it('creates a link copy with only the selected media, no location or tags, and re-encrypted thumbnails', async () => {
    const photo = fakeJpeg(3000, 4);
    const video = mp4WithLocation();
    const first = await syncedEntry([photo, video]);
    const second = await syncedEntry([fakeJpeg(2000, 6)]);
    const firstMeta = await harness.service.readMetadata(first);
    const secondMeta = await harness.service.readMetadata(second);
    const selected = new Set([firstMeta.media[0]?.mediaId ?? '', firstMeta.media[1]?.mediaId ?? '']);
    const progress: string[] = [];

    const result = await service.create(
      { entryIds: [first, second], mediaIds: selected, title: 'Picnic', expiry: '7d', includeNotes: false },
      (step) => progress.push(step.step),
      new AbortController().signal,
    );

    expect(result.linkId).toBe(LINK_ID);
    const fragment = parseLinkFragment(new URL(result.url).hash);
    expect(fragment).not.toBeNull();
    expect(result.url.startsWith(`${BASE_URL}/s/${LINK_ID}#`)).toBe(true);
    expect(result.skippedEntries).toBe(0);
    expect(new Set(progress)).toEqual(new Set(['preparing', 'uploading', 'registering']));

    const order = fake.requests.map((request) => `${request.method} ${request.path.replace(/[A-Za-z0-9_-]{22}/g, ':id')}`);
    expect(order[0]).toBe(`POST ${ROUTES.collections()}`);
    expect(order.at(-1)).toBe(`POST ${ROUTES.publicLinks()}`);

    const collection = requestOf('POST', ROUTES.collections())[0]?.body as { id: string; kind: string; keyByOwner: string; metadataBlob: string };
    expect(collection.kind).toBe('link');
    const secret = unwrapKey(sodium, harness.fixture.keys.masterKey, decodeBytes(collection.keyByOwner as never), AD_CONTEXTS.linkSecretByMaster, collection.id);
    expect(secret).toEqual(fragment);
    const linkKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkCollectionKey, KDF_CONTEXTS.link, secret);
    const accessToken = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkAccessToken, KDF_CONTEXTS.link, secret);
    expect(openJson(sodium, linkKey, decodeBytes(collection.metadataBlob as never), CollectionMetadataV1, 1, new Map(), AD_CONTEXTS.collectionMetadata, collection.id).name).toBe('Picnic');
    expect((requestOf('POST', ROUTES.publicLinks())[0]?.body as { accessToken: string }).accessToken).toBe(encodeBytes(accessToken));

    const entries = requestOf('POST', ROUTES.entries()).map((request) => request.body as {
      id: string;
      purpose: string;
      metadataBlob: string;
      memberships: { collectionId: string; entryKeyByCollection: string }[];
      media: { mediaId: string; mediaKeyByEntry: string }[];
    });
    expect(entries).toHaveLength(1);
    const copy = entries[0];
    if (copy === undefined) return;
    expect(copy.purpose).toBe('link_copy');
    expect(copy.memberships.map((membership) => membership.collectionId)).toEqual([collection.id]);
    const entryKey = unwrapKey(sodium, linkKey, decodeBytes(copy.memberships[0]?.entryKeyByCollection as never), AD_CONTEXTS.entryKeyByCollection, `${collection.id}/${copy.id}`);
    const metadata = openJson(sodium, entryKey, decodeBytes(copy.metadataBlob as never), EntryMetadataV1, 1, new Map(), AD_CONTEXTS.entryMetadata, copy.id);
    expect(metadata.location).toBeNull();
    expect(metadata.tags).toEqual([]);
    expect(metadata.note).toBe('');
    expect(metadata.takenAtUtc).toBe(firstMeta.takenAtUtc);
    expect(metadata.media.map((media: MediaDescriptor) => media.mimeType)).toEqual(['image/jpeg', 'video/mp4']);
    expect(metadata.media.every((media: MediaDescriptor) => media.capturedAtUtc === null && media.originalFileName === null)).toBe(true);
    expect(secondMeta.media).toHaveLength(1);

    const presigns = requestOf('POST', ROUTES.objectsPresign()).map((request) => request.body as { id: string; mediaId: string; variant: string });
    expect(presigns).toHaveLength(4);
    const sourceThumbnail = await harness.thumbnailPlaintext(first, firstMeta.media[0]?.mediaId ?? '');
    for (const [index, media] of metadata.media.entries()) {
      const wrapped = copy.media.find((item) => item.mediaId === media.mediaId)?.mediaKeyByEntry ?? '';
      const mediaKey = unwrapKey(sodium, entryKey, decodeBytes(wrapped as never), AD_CONTEXTS.mediaKeyByEntry, `${copy.id}/${media.mediaId}`);
      const thumbKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaThumbnail, KDF_CONTEXTS.media, mediaKey);
      const originalKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaOriginal, KDF_CONTEXTS.media, mediaKey);
      const thumbObject = presigns.find((presign) => presign.mediaId === media.mediaId && presign.variant === 'thumbnail');
      const originalObject = presigns.find((presign) => presign.mediaId === media.mediaId && presign.variant === 'original');
      expect(decryptUploaded(thumbObject?.id ?? '', thumbKey, media.thumbnail.plaintextBytes)).toEqual(sourceThumbnail);
      const plain = decryptUploaded(originalObject?.id ?? '', originalKey, media.originalPlaintextBytes);
      if (index === 0) expect(plain).toEqual(photo);
      else expect(plain.length).toBeLessThan(video.length);
    }
    for (const id of uploaded.keys()) expect(await harness.fixture.fileStore.size(id)).toBeNull();
  });

  it('keeps the note when includeNotes is on', async () => {
    const entryId = await syncedEntry([fakeJpeg(2000, 2)], 'see you **soon**');
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    await service.create({ entryIds: [entryId], mediaIds: new Set([mediaId]), title: 'Note', expiry: '1d', includeNotes: true }, () => undefined, new AbortController().signal);
    const copy = requestOf('POST', ROUTES.entries())[0]?.body as { id: string; metadataBlob: string; memberships: { entryKeyByCollection: string }[] };
    const collection = requestOf('POST', ROUTES.collections())[0]?.body as { id: string; keyByOwner: string };
    const secret = unwrapKey(sodium, harness.fixture.keys.masterKey, decodeBytes(collection.keyByOwner as never), AD_CONTEXTS.linkSecretByMaster, collection.id);
    const linkKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkCollectionKey, KDF_CONTEXTS.link, secret);
    const entryKey = unwrapKey(sodium, linkKey, decodeBytes(copy.memberships[0]?.entryKeyByCollection as never), AD_CONTEXTS.entryKeyByCollection, `${collection.id}/${copy.id}`);
    expect(openJson(sodium, entryKey, decodeBytes(copy.metadataBlob as never), EntryMetadataV1, 1, new Map(), AD_CONTEXTS.entryMetadata, copy.id).note).toBe('see you **soon**');
  });

  it('stops and deletes the link collection when the stripper verification fails', async () => {
    const entryId = await syncedEntry([fakeJpeg(2000, 3)]);
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    const failing = createService({
      strip: async () => {
        throw new StripFailedError();
      },
    });
    await expect(
      failing.create({ entryIds: [entryId], mediaIds: new Set([mediaId]), title: 'x', expiry: '7d', includeNotes: false }, () => undefined, new AbortController().signal),
    ).rejects.toBeInstanceOf(StripFailedError);
    const collection = requestOf('POST', ROUTES.collections())[0]?.body as { id: string };
    expect(requestOf('DELETE', ROUTES.collection(collection.id))).toHaveLength(1);
    expect(requestOf('POST', ROUTES.publicLinks())).toHaveLength(0);
  });

  it('creates a link for an entry that is still uploading, from the local copies (D-100)', async () => {
    const pending = await harness.createEntry([fakeJpeg(2000, 7)]);
    const mediaId = (await harness.service.readMetadata(pending)).media[0]?.mediaId ?? '';
    const created = await service.create({ entryIds: [pending], mediaIds: new Set([mediaId]), title: 'x', expiry: '7d', includeNotes: false }, () => undefined, new AbortController().signal);
    expect(created.url).toContain('#');
  });

  it('refuses media that do not belong to the entries', async () => {
    const synced = await syncedEntry([fakeJpeg(2000, 8)]);
    await expect(
      service.create({ entryIds: [synced], mediaIds: new Set(['X'.repeat(22)]), title: 'x', expiry: '7d', includeNotes: false }, () => undefined, new AbortController().signal),
    ).rejects.toThrow();
    expect(requestOf('POST', ROUTES.collections())).toHaveLength(0);
  });

  it('list rebuilds the exact URL from the link collection key', async () => {
    const entryId = await syncedEntry([fakeJpeg(2000, 9)]);
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    const created = await service.create({ entryIds: [entryId], mediaIds: new Set([mediaId]), title: 'Trip', expiry: 'one_view', includeNotes: false }, () => undefined, new AbortController().signal);
    const collection = requestOf('POST', ROUTES.collections())[0]?.body as { id: string; keyByOwner: string; metadataBlob: string };
    fake.on('GET', ROUTES.publicLinks(), () => ({
      items: [{ linkId: LINK_ID, collectionId: collection.id, expiresAt: null, maxViews: 1, viewCount: 0, createdAt: NOW }],
    }));
    fake.on('GET', ROUTES.collection(collection.id), () => ({
      id: collection.id,
      kind: 'link',
      role: 'owner',
      ownerId: 'O'.repeat(22),
      ownerUsername: 'ada',
      keyMaterial: collection.keyByOwner,
      metadataBlob: collection.metadataBlob,
      lastSeq: 1,
      createdAt: NOW,
      metaUpdatedAt: NOW,
      recipientCount: null,
    }));
    const links = await service.list();
    expect(links).toEqual([{ linkId: LINK_ID, url: created.url, title: 'Trip', expiresAt: null, maxViews: 1, viewCount: 0, createdAt: NOW }]);
  });
});

describe('link url', () => {
  it('round-trips the secret through the fragment and rejects bad fragments', () => {
    const secret = Uint8Array.from({ length: 32 }, (_, index) => index);
    const url = buildLinkUrl('https://a.example/', LINK_ID, secret);
    expect(url).toBe(`https://a.example/s/${LINK_ID}#${encodeBytes(secret)}`);
    expect(parseLinkFragment(new URL(url).hash)).toEqual(secret);
    expect(parseLinkFragment('')).toBeNull();
    expect(parseLinkFragment('#short')).toBeNull();
    expect(parseLinkFragment('#!!!')).toBeNull();
  });
});

describe('decrypting byte source', () => {
  it('reads random ranges across chunk boundaries as plaintext', async () => {
    const plaintext = seededBytes(3 * 1_048_576 + 777, 2);
    const entryId = await harness.createEntry([concat(fakeJpeg(10, 1).subarray(0, 4), plaintext)]);
    const media = (await harness.service.readMetadata(entryId)).media[0];
    const objects = await harness.fixture.rowStore.transaction((tx) => tx.objects.byMedia(media?.mediaId ?? ''));
    const original = objects.find((row) => row.variant === 'original');
    const key = await harness.entryKeys.variantKey(entryId, media?.mediaId ?? '', 'original');
    const source = await createDecryptingByteSource(sodium, harness.fixture.fileStore, original?.id ?? '', key, original?.plaintextSize ?? 0);
    const expected = concat(fakeJpeg(10, 1).subarray(0, 4), plaintext);
    expect(source.size).toBe(expected.length);
    for (const [offset, length] of [[0, 10], [1_048_570, 20], [2_097_100, 1_048_700], [expected.length - 5, 50]] as const) {
      expect(await source.read(offset, length)).toEqual(expected.slice(offset, offset + length));
    }
    expect(byteSourceFromBytes(expected).size).toBe(source.size);
  });
});

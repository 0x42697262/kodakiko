import { EntryMetadataV1, ROUTES, downloadResponse } from '@kodakiko/contract';
import {
  AD_CONTEXTS,
  AEAD_TAG_BYTES,
  STREAM_HEADER_BYTES,
  bytesEqual,
  concatBytes,
  createStreamDecryptor,
  openJson,
  wipe,
} from '@kodakiko/core';
import { sealEntryMetadata, type Metadata } from '@kodakiko/client/test-support';
import { importIntoTray } from '@kodakiko/client/testing';
import { SETTLE_DEADLINE_MS, SETTLE_POLL_MS } from './constants.ts';
import type { Device } from './device.ts';

const METADATA_SCHEMA_VERSION = 1;
const NO_MIGRATIONS = new Map<number, (raw: Record<string, unknown>) => Record<string, unknown>>();

export type EntryInput = {
  readonly mediaBytes: readonly Uint8Array[];
  readonly note: string;
  readonly tags: readonly string[];
};

export type CreatedEntry = {
  readonly entryId: string;
  readonly mediaIds: readonly string[];
};

export type EntrySnapshot = { readonly revision: number; readonly blob: string };

function pause(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function toHex(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString('hex');
}

export async function homeCollectionId(device: Device): Promise<string> {
  const collections = await device.context.rowStore.transaction((tx) => tx.collections.all());
  const home = collections.find((collection) => collection.kind === 'home');
  if (home === undefined) throw new Error(`${device.name} has no home collection yet`);
  return home.id;
}

export async function createEntryLocally(
  device: Device,
  input: EntryInput,
): Promise<CreatedEntry> {
  const mediaIds = await importIntoTray(
    device.context,
    input.mediaBytes.map((bytes, position) => ({ bytes, fileName: `media-${position}.jpg` })),
  );
  const entryId = await device.context.entries.createFromTray({
    note: input.note,
    tags: input.tags,
    time: null,
  });
  return { entryId, mediaIds };
}

export async function readEntry(device: Device, entryId: string) {
  return device.context.rowStore.transaction((tx) => tx.entries.get(entryId));
}

export async function readMetadata(
  device: Device,
  entryId: string,
  which: 'local' | 'base' = 'local',
): Promise<Metadata> {
  const entry = await readEntry(device, entryId);
  if (entry === null) throw new Error(`${device.name} has no entry ${entryId}`);
  const blob = which === 'local' ? entry.metadataBlob : entry.baseMetadataBlob;
  if (blob === null) throw new Error(`${device.name} has no base blob for ${entryId}`);
  const entryKey = await device.context.entryKeys.entryKey(entryId);
  return openJson(
    device.context.sodium,
    entryKey,
    blob,
    EntryMetadataV1,
    METADATA_SCHEMA_VERSION,
    NO_MIGRATIONS,
    AD_CONTEXTS.entryMetadata,
    entryId,
  );
}

async function enqueue(device: Device, kind: string, entryId: string): Promise<void> {
  await device.context.rowStore.transaction((tx) =>
    tx.outbox.enqueue({
      kind,
      payload: { entryId },
      state: 'queued',
      createdAt: Date.now(),
      attempts: 0,
      nextAttemptAt: 0,
      lastErrorCode: null,
    }),
  );
}

export async function editEntryLocally(
  device: Device,
  entryId: string,
  change: (metadata: Metadata) => Metadata,
  editedAtUtc: string,
): Promise<void> {
  const entryKey = await device.context.entryKeys.entryKey(entryId);
  const current = await readMetadata(device, entryId);
  const next: Metadata = { ...change(current), editedAtUtc };
  const blob = sealEntryMetadata(device.context, entryKey, entryId, next);
  await device.context.rowStore.transaction(async (tx) => {
    const entry = await tx.entries.get(entryId);
    if (entry === null) throw new Error(`${device.name} has no entry ${entryId}`);
    await tx.entries.put({
      ...entry,
      metadataBlob: blob,
      localState: entry.localState === 'synced' ? 'pending_update' : entry.localState,
    });
  });
  await enqueue(device, 'updateEntryMeta', entryId);
}

export async function trashEntryLocally(device: Device, entryId: string): Promise<void> {
  await device.context.rowStore.transaction(async (tx) => {
    const entry = await tx.entries.get(entryId);
    if (entry === null) throw new Error(`${device.name} has no entry ${entryId}`);
    await tx.entries.put({ ...entry, trashedAt: new Date().toISOString() });
  });
  await enqueue(device, 'trashEntry', entryId);
}

export async function deleteEntryLocally(device: Device, entryId: string): Promise<void> {
  await enqueue(device, 'deleteEntry', entryId);
}

export async function outboxRows(device: Device) {
  return device.context.rowStore.transaction((tx) => tx.outbox.all());
}

export async function syncUntilIdle(device: Device): Promise<void> {
  const deadline = Date.now() + SETTLE_DEADLINE_MS;
  for (;;) {
    await device.context.sync.syncNow();
    const queued = (await outboxRows(device)).filter((row) => row.state === 'queued');
    if (queued.length === 0) return;
    if (Date.now() > deadline) {
      throw new Error(
        `${device.name} still has ${queued.map((row) => `${row.kind}#${row.id}`).join(', ')} queued`,
      );
    }
    await pause(SETTLE_POLL_MS);
  }
}

export async function entrySnapshots(device: Device): Promise<ReadonlyMap<string, EntrySnapshot>> {
  const entries = await device.context.rowStore.transaction((tx) => tx.entries.all());
  return new Map(
    entries.map((entry) => [entry.id, { revision: entry.revision, blob: toHex(entry.metadataBlob) }]),
  );
}

export async function downloadAndDecrypt(
  device: Device,
  entryId: string,
  mediaId: string,
  variant: 'original' | 'thumbnail',
): Promise<Uint8Array> {
  const object = await device.context.rowStore.transaction(async (tx) =>
    (await tx.objects.byMedia(mediaId)).find((candidate) => candidate.variant === variant),
  );
  if (object === undefined) throw new Error(`${device.name} has no ${variant} object for ${mediaId}`);
  const reference = await device.context.http.request({
    method: 'GET',
    path: ROUTES.objectDownload(object.id),
    responseSchema: downloadResponse,
    auth: 'session',
    idempotent: true,
  });
  const ciphertext = new Uint8Array(await (await fetch(reference.url)).arrayBuffer());
  const variantKey = await device.context.entryKeys.variantKey(entryId, mediaId, variant);
  try {
    const decryptor = createStreamDecryptor(
      device.context.sodium,
      variantKey,
      ciphertext.slice(0, STREAM_HEADER_BYTES),
      object.plaintextSize,
    );
    const { layout } = decryptor;
    const parts: Uint8Array[] = [];
    let offset = STREAM_HEADER_BYTES;
    for (let index = 0; index < layout.chunkCount; index += 1) {
      const isLast = index === layout.chunkCount - 1;
      const plaintextLength = isLast
        ? layout.plaintextSize - layout.chunkSize * (layout.chunkCount - 1)
        : layout.chunkSize;
      const length = plaintextLength + AEAD_TAG_BYTES;
      parts.push(decryptor.decryptNext(ciphertext.slice(offset, offset + length)));
      offset += length;
    }
    decryptor.finish();
    return concatBytes(...parts);
  } finally {
    wipe(variantKey);
  }
}

export function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return bytesEqual(left, right);
}

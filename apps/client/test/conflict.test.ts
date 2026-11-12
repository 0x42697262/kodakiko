import { beforeEach, describe, expect, it } from 'vitest';
import { AD_CONTEXTS, openJson, type Sodium } from '@kodakiko/core';
import { EntryMetadataV1 } from '@kodakiko/contract';
import { createEntryKeys } from '../src/services/keys/entry-keys';
import { createConflictResolver } from '../src/services/sync/conflict';
import {
  createAccountFixture,
  createLocalEntry,
  createMemoryFileStore,
  createMemoryRowStore,
  createNodeSodium,
  sealEntryMetadata,
  type AccountFixture,
  type LocalEntry,
  type Metadata,
} from './support';

let sodium: Sodium;
let fixture: AccountFixture;
let local: LocalEntry;

const NOW = '2026-11-20T10:00:00.000Z';
const EARLY = '2026-11-15T10:00:00.000Z';
const LATE = '2026-11-16T10:00:00.000Z';

function seal(metadata: Metadata): Uint8Array {
  return sealEntryMetadata(fixture, local.entryKey, local.entryId, metadata);
}

function open(blob: Uint8Array): Metadata {
  return openJson(sodium, local.entryKey, blob, EntryMetadataV1, 1, new Map(), AD_CONTEXTS.entryMetadata, local.entryId);
}

async function entryRow() {
  const row = await fixture.rowStore.transaction((tx) => tx.entries.get(local.entryId));
  if (row === null) throw new Error('missing entry');
  return row;
}

async function prepareSynced(ancestor: Metadata, localMetadata: Metadata) {
  const ancestorBlob = seal(ancestor);
  const localBlob = seal(localMetadata);
  await fixture.rowStore.transaction(async (tx) => {
    const row = await tx.entries.get(local.entryId);
    if (row === null) return;
    await tx.entries.put({
      ...row,
      revision: 1,
      baseRevision: 1,
      baseMetadataBlob: ancestorBlob,
      metadataBlob: localBlob,
      localState: 'pending_update',
    });
  });
  return { ancestorBlob, localBlob };
}

function resolver() {
  const entryKeys = createEntryKeys({ sodium, rowStore: fixture.rowStore, account: () => fixture.keys });
  return createConflictResolver({
    sodium,
    rowStore: fixture.rowStore,
    entryKeys,
    clock: { now: () => 0, nowIso: () => NOW },
  });
}

beforeEach(async () => {
  sodium = await createNodeSodium();
  fixture = await createAccountFixture(sodium, createMemoryRowStore(), createMemoryFileStore());
  local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: 'base', tags: ['a'], enqueue: false });
});

describe('conflict resolution', () => {
  it('merges local and server edits, stores the merged blob on the server revision and asks for a retry', async () => {
    const ancestor = local.metadata;
    const { localBlob } = await prepareSynced(ancestor, { ...ancestor, note: 'local note', editedAtUtc: EARLY });
    const serverBlob = seal({ ...ancestor, tags: ['a', 'b'], editedAtUtc: LATE });
    const outcome = await resolver().resolve(local.entryId, { revision: 2, metadataBlob: serverBlob });
    expect(outcome).toBe('retry');
    const row = await entryRow();
    expect(row.baseRevision).toBe(2);
    expect(row.revision).toBe(2);
    expect([...(row.baseMetadataBlob ?? [])]).toEqual([...serverBlob]);
    expect(row.localState).toBe('pending_update');
    expect([...row.metadataBlob]).not.toEqual([...localBlob]);
    const merged = open(row.metadataBlob);
    expect(merged.note).toBe('local note');
    expect(merged.tags).toEqual(['a', 'b']);
    expect(merged.editedAtUtc).toBe(NOW);
  });

  it('adopts the server blob and revision when the merge equals the server metadata', async () => {
    const ancestor = local.metadata;
    const edited = { ...ancestor, note: 'edited', editedAtUtc: EARLY };
    await prepareSynced(ancestor, edited);
    const serverBlob = seal({ ...edited, editedAtUtc: LATE });
    const outcome = await resolver().resolve(local.entryId, { revision: 2, metadataBlob: serverBlob });
    expect(outcome).toBe('adopted');
    const row = await entryRow();
    expect([...row.metadataBlob]).toEqual([...serverBlob]);
    expect([...(row.baseMetadataBlob ?? [])]).toEqual([...serverBlob]);
    expect(row.revision).toBe(2);
    expect(row.baseRevision).toBe(2);
    expect(row.localState).toBe('synced');
  });

  it('clears the stored server-pending copy after resolving', async () => {
    const ancestor = local.metadata;
    await prepareSynced(ancestor, { ...ancestor, note: 'x', editedAtUtc: EARLY });
    const serverBlob = seal({ ...ancestor, note: 'y', editedAtUtc: LATE });
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(local.entryId);
      if (row !== null) await tx.entries.put({ ...row, serverPendingBlob: serverBlob, serverPendingRevision: 2 });
    });
    await resolver().resolve(local.entryId, { revision: 2, metadataBlob: serverBlob });
    const row = await entryRow();
    expect(row.serverPendingBlob).toBeNull();
    expect(row.serverPendingRevision).toBeNull();
  });

  it('keeps the later note when both sides changed it', async () => {
    const ancestor = local.metadata;
    await prepareSynced(ancestor, { ...ancestor, note: 'local', editedAtUtc: EARLY });
    const serverBlob = seal({ ...ancestor, note: 'server', editedAtUtc: LATE });
    await resolver().resolve(local.entryId, { revision: 2, metadataBlob: serverBlob });
    expect(open((await entryRow()).metadataBlob).note).toBe('server');
  });

  it('treats a missing entry as adopted', async () => {
    const outcome = await resolver().resolve(fixture.randomId(), { revision: 2, metadataBlob: Uint8Array.of(1) });
    expect(outcome).toBe('adopted');
  });

  it('does not lose an edit made while the merge was being computed', async () => {
    const ancestor = local.metadata;
    await prepareSynced(ancestor, { ...ancestor, note: 'first local edit', editedAtUtc: EARLY });
    const serverBlob = seal({ ...ancestor, tags: ['a', 'b'], editedAtUtc: LATE });
    const realKeys = createEntryKeys({ sodium, rowStore: fixture.rowStore, account: () => fixture.keys });
    let interfered = false;
    const interferingKeys = {
      ...realKeys,
      entryKey: async (entryId: string) => {
        const key = await realKeys.entryKey(entryId);
        if (!interfered) {
          interfered = true;
          await fixture.rowStore.transaction(async (tx) => {
            const row = await tx.entries.get(entryId);
            if (row !== null) {
              await tx.entries.put({
                ...row,
                metadataBlob: seal({ ...ancestor, note: 'edit during merge', editedAtUtc: LATE }),
              });
            }
          });
        }
        return key;
      },
    };
    const racing = createConflictResolver({
      sodium,
      rowStore: fixture.rowStore,
      entryKeys: interferingKeys,
      clock: { now: () => 0, nowIso: () => NOW },
    });
    const outcome = await racing.resolve(local.entryId, { revision: 2, metadataBlob: serverBlob });
    expect(outcome).toBe('retry');
    const merged = open((await entryRow()).metadataBlob);
    expect(merged.note).toBe('edit during merge');
    expect(merged.tags).toEqual(['a', 'b']);
    expect((await entryRow()).baseRevision).toBe(2);
  });

  it('gives up with a retry outcome instead of looping forever when edits keep arriving', async () => {
    const ancestor = local.metadata;
    await prepareSynced(ancestor, { ...ancestor, note: 'start', editedAtUtc: EARLY });
    const serverBlob = seal({ ...ancestor, tags: ['a', 'b'], editedAtUtc: LATE });
    const realKeys = createEntryKeys({ sodium, rowStore: fixture.rowStore, account: () => fixture.keys });
    let counter = 0;
    const racing = createConflictResolver({
      sodium,
      rowStore: fixture.rowStore,
      entryKeys: {
        ...realKeys,
        entryKey: async (entryId: string) => {
          const key = await realKeys.entryKey(entryId);
          counter += 1;
          await fixture.rowStore.transaction(async (tx) => {
            const row = await tx.entries.get(entryId);
            if (row !== null) {
              await tx.entries.put({ ...row, metadataBlob: seal({ ...ancestor, note: `edit ${counter}`, editedAtUtc: LATE }) });
            }
          });
          return key;
        },
      },
      clock: { now: () => 0, nowIso: () => NOW },
    });
    await expect(racing.resolve(local.entryId, { revision: 2, metadataBlob: serverBlob })).resolves.toBe('retry');
    expect(counter).toBeLessThan(20);
    expect((await entryRow()).baseRevision).toBe(1);
  });
});

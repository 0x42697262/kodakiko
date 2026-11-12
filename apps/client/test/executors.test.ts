import { beforeEach, describe, expect, it } from 'vitest';
import { ROUTES, decodeBytes, encodeBytes, type Bytes } from '@kodakiko/contract';
import { bytesEqual, type Sodium } from '@kodakiko/core';
import { NetworkError } from '../src/adapters/http/http';
import type { OutboxRow } from '../src/adapters/rowstore/rowstore';
import { createEventBus, type AppEvents } from '../src/composition/events';
import { EXECUTORS } from '../src/services/sync/executors';
import type { ExecutorDeps, ExecutorResult } from '../src/services/sync/executors/executor-types';
import {
  createAccountFixture,
  createLocalEntry,
  createMemoryFileStore,
  createMemoryRowStore,
  createNodeSodium,
  sealEntryMetadata,
  type AccountFixture,
  type LocalEntry,
} from './support';
import { apiFailure, createFakeHttp, type FakeHttp } from './support/fake-http';

let sodium: Sodium;
let fixture: AccountFixture;
let local: LocalEntry;
let fakeHttp: FakeHttp;
let deps: ExecutorDeps;
let enqueued: string[][];
let resolveCalls: Array<{ entryId: string; revision: number }>;
let resolveOutcome: 'adopted' | 'retry';
let changed: string[];
let logged: string[];

const TIME = '2026-11-12T10:00:00.000Z';
const OWNER_ID = 'OOOOOOOOOOOOOOOOOOOOOO';

function entryDtoFor(revision: number, blob: Uint8Array, trashedAt: string | null = null) {
  return {
    id: local.entryId,
    ownerId: OWNER_ID,
    purpose: 'journal',
    revision,
    metadataBlob: encodeBytes(blob),
    trashedAt,
    createdAt: TIME,
    updatedAt: TIME,
  };
}

async function entryRow() {
  const row = await fixture.rowStore.transaction((tx) => tx.entries.get(local.entryId));
  if (row === null) throw new Error('missing entry');
  return row;
}

async function setObjectStates(state: 'uploaded' | 'local' | 'queued' | 'uploading') {
  await fixture.rowStore.transaction(async (tx) => {
    for (const object of await tx.objects.all()) await tx.objects.put({ ...object, uploadState: state });
  });
}

function outboxItem(kind: OutboxRow['kind'], payload: unknown): OutboxRow {
  return { id: 1, kind, payload, state: 'queued', createdAt: 0, attempts: 0, nextAttemptAt: 0, lastErrorCode: null };
}

async function run(kind: keyof typeof EXECUTORS, payload: unknown = { entryId: local.entryId }): Promise<ExecutorResult> {
  return EXECUTORS[kind](outboxItem(kind, payload), deps);
}

beforeEach(async () => {
  sodium = await createNodeSodium();
  fixture = await createAccountFixture(sodium, createMemoryRowStore(), createMemoryFileStore());
  local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1, 2, 3)], note: 'first', tags: ['a'] });
  fakeHttp = createFakeHttp();
  enqueued = [];
  resolveCalls = [];
  resolveOutcome = 'adopted';
  changed = [];
  logged = [];
  const events = createEventBus<AppEvents>();
  events.on('entriesChanged', (ids) => changed.push(...ids));
  deps = {
    http: fakeHttp.http,
    rowStore: fixture.rowStore,
    fileStore: fixture.fileStore,
    events,
    log: { warn: (event) => void logged.push(event) },
    uploads: {
      enqueueObjectUploads: async (ids) => {
        enqueued.push([...ids]);
        await fixture.rowStore.transaction(async (tx) => {
          for (const id of ids) {
            const object = await tx.objects.get(id);
            if (object !== null) await tx.objects.put({ ...object, uploadState: 'uploading' });
          }
        });
      },
    },
    conflict: {
      resolve: async (entryId, server) => {
        resolveCalls.push({ entryId, revision: server.revision });
        return resolveOutcome;
      },
    },
  };
});

describe('createEntry', () => {
  it('posts the current blob, memberships and media, then records the base copy', async () => {
    const before = await entryRow();
    fakeHttp.on('POST', ROUTES.entries(), () => entryDtoFor(1, before.metadataBlob));
    const result = await run('createEntry');
    const body = fakeHttp.requests[0]?.body as {
      id: string;
      purpose: string;
      metadataBlob: string;
      memberships: Array<{ collectionId: string }>;
      media: Array<{ mediaId: string }>;
    };
    expect(body.id).toBe(local.entryId);
    expect(body.purpose).toBe('journal');
    expect([...decodeBytes(body.metadataBlob as Bytes)]).toEqual([...before.metadataBlob]);
    expect(body.memberships.map((item) => item.collectionId)).toEqual([fixture.homeCollectionId]);
    expect(body.media.map((item) => item.mediaId)).toEqual([...local.mediaIds]);
    const after = await entryRow();
    expect(after.baseRevision).toBe(1);
    expect([...(after.baseMetadataBlob ?? [])]).toEqual([...before.metadataBlob]);
    expect(result).toBe('waiting');
  });

  it('enqueues uploads for local objects and waits until all are uploaded', async () => {
    const before = await entryRow();
    fakeHttp.on('POST', ROUTES.entries(), () => entryDtoFor(1, before.metadataBlob));
    const result = await run('createEntry');
    expect(enqueued).toHaveLength(1);
    expect(enqueued[0]?.sort()).toEqual([...local.originalObjectIds, ...local.thumbnailObjectIds].sort());
    expect(result).toBe('waiting');
    expect((await entryRow()).localState).toBe('pending_create');
  });

  it('finishes as synced when every object is uploaded and the blob is unchanged', async () => {
    const before = await entryRow();
    fakeHttp.on('POST', ROUTES.entries(), () => entryDtoFor(1, before.metadataBlob));
    await run('createEntry');
    await setObjectStates('uploaded');
    expect(await run('createEntry')).toBe('done');
    expect((await entryRow()).localState).toBe('synced');
    expect(fakeHttp.count('POST', ROUTES.entries())).toBe(1);
  });

  it('marks the entry pending_update when it was edited after the POST', async () => {
    const before = await entryRow();
    fakeHttp.on('POST', ROUTES.entries(), () => entryDtoFor(1, before.metadataBlob));
    await run('createEntry');
    await setObjectStates('uploaded');
    const edited = sealEntryMetadata(fixture, local.entryKey, local.entryId, { ...local.metadata, note: 'edited' });
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(local.entryId);
      if (row !== null) await tx.entries.put({ ...row, metadataBlob: edited });
    });
    expect(await run('createEntry')).toBe('done');
    const after = await entryRow();
    expect(after.localState).toBe('pending_update');
    expect(bytesEqual(after.baseMetadataBlob ?? new Uint8Array(), before.metadataBlob)).toBe(true);
  });

  it('skips the POST when the base revision is already 1', async () => {
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(local.entryId);
      if (row !== null) await tx.entries.put({ ...row, baseRevision: 1, baseMetadataBlob: row.metadataBlob });
    });
    await setObjectStates('uploaded');
    expect(await run('createEntry')).toBe('done');
    expect(fakeHttp.requests).toHaveLength(0);
  });

  it('skips the POST and adopts the pulled server copy as the base when one is stored', async () => {
    const serverBlob = Uint8Array.of(4, 4, 4);
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(local.entryId);
      if (row !== null) await tx.entries.put({ ...row, serverPendingBlob: serverBlob, serverPendingRevision: 1 });
    });
    const result = await run('createEntry');
    expect(fakeHttp.count('POST', ROUTES.entries())).toBe(0);
    const after = await entryRow();
    expect(after.baseRevision).toBe(1);
    expect(after.revision).toBe(1);
    expect([...(after.baseMetadataBlob ?? [])]).toEqual([...serverBlob]);
    expect(after.serverPendingBlob).toBeNull();
    expect(after.serverPendingRevision).toBeNull();
    expect(result).toBe('waiting');
    await setObjectStates('uploaded');
    expect(await run('createEntry')).toBe('done');
    expect((await entryRow()).localState).toBe('pending_update');
  });

  it('adopts the server copy after 409 ID_CONFLICT when the entry is owned and continues', async () => {
    const serverBlob = Uint8Array.of(6, 6);
    fakeHttp.on('POST', ROUTES.entries(), () => {
      throw apiFailure(409, 'ID_CONFLICT');
    });
    fakeHttp.on('GET', ROUTES.entry(local.entryId), () => entryDtoFor(1, serverBlob));
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(local.entryId);
      if (row !== null) await tx.entries.put({ ...row, ownerId: OWNER_ID });
    });
    const before = await entryRow();
    const result = await run('createEntry');
    expect(fakeHttp.count('GET', ROUTES.entry(local.entryId))).toBe(1);
    const after = await entryRow();
    expect(after.baseRevision).toBe(1);
    expect([...(after.baseMetadataBlob ?? [])]).toEqual([...serverBlob]);
    expect([...after.metadataBlob]).toEqual([...before.metadataBlob]);
    expect(result).toBe('waiting');
    expect(enqueued).toHaveLength(1);
  });

  it('reports ID_CONFLICT as a failure when the existing entry belongs to someone else', async () => {
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(local.entryId);
      if (row !== null) await tx.entries.put({ ...row, ownerId: 'someone-else-locally' });
    });
    fakeHttp.on('POST', ROUTES.entries(), () => {
      throw apiFailure(409, 'ID_CONFLICT');
    });
    fakeHttp.on('GET', ROUTES.entry(local.entryId), () => entryDtoFor(1, Uint8Array.of(6)));
    expect(await run('createEntry')).toEqual({
      failure: { kind: 'http', status: 409, code: 'ID_CONFLICT', retryAfterSeconds: null },
    });
    expect((await entryRow()).baseRevision).toBe(0);
  });

  it('reports http failures as push failures', async () => {
    fakeHttp.on('POST', ROUTES.entries(), () => {
      throw apiFailure(403, 'QUOTA_EXCEEDED');
    });
    expect(await run('createEntry')).toEqual({
      failure: { kind: 'http', status: 403, code: 'QUOTA_EXCEEDED', retryAfterSeconds: null },
    });
  });

  it('reports network errors as push failures', async () => {
    fakeHttp.on('POST', ROUTES.entries(), () => {
      throw new NetworkError();
    });
    expect(await run('createEntry')).toEqual({ failure: { kind: 'network' } });
  });

  it('does nothing when the entry no longer exists locally', async () => {
    expect(await run('createEntry', { entryId: fixture.randomId() })).toBe('done');
    expect(fakeHttp.requests).toHaveLength(0);
  });

  it('propagates unexpected errors', async () => {
    fakeHttp.on('POST', ROUTES.entries(), () => {
      throw new TypeError('bug');
    });
    await expect(run('createEntry')).rejects.toBeInstanceOf(TypeError);
  });
});

describe('updateEntryMeta', () => {
  async function prepareSynced() {
    const row = await entryRow();
    await fixture.rowStore.transaction((tx) =>
      tx.entries.put({ ...row, localState: 'synced', revision: 1, baseRevision: 1, baseMetadataBlob: row.metadataBlob }),
    );
    return row.metadataBlob;
  }

  async function editNote(note: string): Promise<Uint8Array> {
    const blob = sealEntryMetadata(fixture, local.entryKey, local.entryId, { ...local.metadata, note });
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(local.entryId);
      if (row !== null) await tx.entries.put({ ...row, metadataBlob: blob, localState: 'pending_update' });
    });
    return blob;
  }

  it('does not call the server when the blob equals the base blob', async () => {
    await prepareSynced();
    expect(await run('updateEntryMeta')).toBe('done');
    expect(fakeHttp.requests).toHaveLength(0);
  });

  it('patches the current blob with the base revision and records the posted copy', async () => {
    await prepareSynced();
    const edited = await editNote('changed');
    fakeHttp.on('PATCH', ROUTES.entry(local.entryId), () => entryDtoFor(2, edited));
    expect(await run('updateEntryMeta')).toBe('done');
    expect(fakeHttp.requests[0]?.body).toEqual({ metadataBlob: encodeBytes(edited), baseRevision: 1 });
    const after = await entryRow();
    expect(after.baseRevision).toBe(2);
    expect(after.revision).toBe(2);
    expect(bytesEqual(after.baseMetadataBlob ?? new Uint8Array(), edited)).toBe(true);
    expect(after.localState).toBe('synced');
  });

  it('re-runs immediately when the user edited while the PATCH was in flight', async () => {
    await prepareSynced();
    const first = await editNote('first edit');
    let second: Uint8Array | null = null;
    fakeHttp.on('PATCH', ROUTES.entry(local.entryId), async (request) => {
      const count = fakeHttp.count('PATCH', ROUTES.entry(local.entryId));
      if (count === 1) second = await editNote('second edit');
      const body = request.body as { metadataBlob: string };
      return entryDtoFor(count + 1, decodeBytes(body.metadataBlob as Bytes));
    });
    expect(await run('updateEntryMeta')).toBe('done');
    expect(fakeHttp.count('PATCH', ROUTES.entry(local.entryId))).toBe(2);
    expect(fakeHttp.requests[0]?.body).toMatchObject({ metadataBlob: encodeBytes(first) });
    expect(fakeHttp.requests[1]?.body).toMatchObject({ metadataBlob: encodeBytes(second ?? new Uint8Array()), baseRevision: 2 });
    expect((await entryRow()).localState).toBe('synced');
  });

  it('resolves a REVISION_CONFLICT through the resolver and retries once', async () => {
    await prepareSynced();
    await editNote('mine');
    resolveOutcome = 'retry';
    const serverBlob = Uint8Array.of(9, 9, 9);
    let calls = 0;
    fakeHttp.on('PATCH', ROUTES.entry(local.entryId), () => {
      calls += 1;
      if (calls === 1) throw apiFailure(409, 'REVISION_CONFLICT', { revision: 2, metadataBlob: encodeBytes(serverBlob) });
      return entryDtoFor(3, Uint8Array.of(1));
    });
    expect(await run('updateEntryMeta')).toBe('done');
    expect(resolveCalls).toEqual([{ entryId: local.entryId, revision: 2 }]);
    expect(calls).toBe(2);
  });

  it('rejects a conflict that reports a revision not newer than the base, logs it and does not merge', async () => {
    await prepareSynced();
    await editNote('mine');
    fakeHttp.on('PATCH', ROUTES.entry(local.entryId), () => {
      throw apiFailure(409, 'REVISION_CONFLICT', { revision: 1, metadataBlob: encodeBytes(Uint8Array.of(7)) });
    });
    expect(await run('updateEntryMeta')).toEqual({
      failure: { kind: 'http', status: 409, code: 'REVISION_CONFLICT', retryAfterSeconds: null },
    });
    expect(resolveCalls).toEqual([]);
    expect(logged).toEqual(['sync.rollback_rejected']);
  });

  it('completes without a second PATCH when the resolver adopts the server blob', async () => {
    await prepareSynced();
    await editNote('mine');
    resolveOutcome = 'adopted';
    fakeHttp.on('PATCH', ROUTES.entry(local.entryId), () => {
      throw apiFailure(409, 'REVISION_CONFLICT', { revision: 2, metadataBlob: encodeBytes(Uint8Array.of(7)) });
    });
    expect(await run('updateEntryMeta')).toBe('done');
    expect(fakeHttp.count('PATCH', ROUTES.entry(local.entryId))).toBe(1);
  });

  it('returns a conflict failure when the retried PATCH conflicts again', async () => {
    await prepareSynced();
    await editNote('mine');
    resolveOutcome = 'retry';
    fakeHttp.on('PATCH', ROUTES.entry(local.entryId), () => {
      throw apiFailure(409, 'REVISION_CONFLICT', { revision: 2, metadataBlob: encodeBytes(Uint8Array.of(7)) });
    });
    expect(await run('updateEntryMeta')).toEqual({
      failure: { kind: 'http', status: 409, code: 'REVISION_CONFLICT', retryAfterSeconds: null },
    });
    expect(resolveCalls).toHaveLength(1);
  });

  it('skips the PATCH and merges straight away when a newer server copy is already stored', async () => {
    await prepareSynced();
    await editNote('mine');
    await fixture.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(local.entryId);
      if (row !== null) await tx.entries.put({ ...row, serverPendingBlob: Uint8Array.of(5), serverPendingRevision: 4 });
    });
    resolveOutcome = 'adopted';
    expect(await run('updateEntryMeta')).toBe('done');
    expect(resolveCalls).toEqual([{ entryId: local.entryId, revision: 4 }]);
    expect(fakeHttp.requests).toHaveLength(0);
  });

  it('maps a 404 to a failure the driver classifies as drop', async () => {
    await prepareSynced();
    await editNote('mine');
    fakeHttp.on('PATCH', ROUTES.entry(local.entryId), () => {
      throw apiFailure(404, 'NOT_FOUND');
    });
    expect(await run('updateEntryMeta')).toEqual({
      failure: { kind: 'http', status: 404, code: 'NOT_FOUND', retryAfterSeconds: null },
    });
  });
});

describe('addMedia', () => {
  it('posts the media row then enqueues only that media\'s objects', async () => {
    const extra = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1), Uint8Array.of(2)], note: '', tags: [], enqueue: false });
    const mediaId = extra.mediaIds[1] ?? '';
    fakeHttp.on('POST', ROUTES.entryMedia(extra.entryId), () => ({
      id: mediaId,
      entryId: extra.entryId,
      mediaKeyByEntry: 'AAAA',
      createdAt: TIME,
    }));
    const result = await EXECUTORS.addMedia(outboxItem('addMedia', { entryId: extra.entryId, mediaId }), deps);
    expect(result).toBe('waiting');
    expect(fakeHttp.requests[0]?.body).toMatchObject({ mediaId });
    expect(enqueued[0]?.sort()).toEqual([extra.originalObjectIds[1] ?? '', extra.thumbnailObjectIds[1] ?? ''].sort());
  });

  it('is done once the media objects are uploaded and does not post again', async () => {
    const mediaId = local.mediaIds[0] ?? '';
    await setObjectStates('uploaded');
    const result = await EXECUTORS.addMedia(outboxItem('addMedia', { entryId: local.entryId, mediaId }), deps);
    expect(result).toBe('done');
    expect(fakeHttp.requests).toHaveLength(0);
  });
});

describe('removeMedia', () => {
  it('deletes the media on the server', async () => {
    const mediaId = local.mediaIds[0] ?? '';
    fakeHttp.on('DELETE', ROUTES.media(mediaId), () => undefined);
    expect(await EXECUTORS.removeMedia(outboxItem('removeMedia', { entryId: local.entryId, mediaId }), deps)).toBe('done');
    expect(fakeHttp.count('DELETE', ROUTES.media(mediaId))).toBe(1);
  });

  it('counts a 404 as done', async () => {
    const mediaId = local.mediaIds[0] ?? '';
    fakeHttp.on('DELETE', ROUTES.media(mediaId), () => {
      throw apiFailure(404, 'NOT_FOUND');
    });
    expect(await EXECUTORS.removeMedia(outboxItem('removeMedia', { entryId: local.entryId, mediaId }), deps)).toBe('done');
  });
});

describe('trashEntry and restoreEntry', () => {
  it('post to the trash and restore routes', async () => {
    const blob = (await entryRow()).metadataBlob;
    fakeHttp.on('POST', ROUTES.entryTrash(local.entryId), () => entryDtoFor(1, blob, TIME));
    fakeHttp.on('POST', ROUTES.entryRestore(local.entryId), () => entryDtoFor(1, blob));
    expect(await run('trashEntry')).toBe('done');
    expect(await run('restoreEntry')).toBe('done');
    expect(fakeHttp.requests.map((request) => request.path)).toEqual([
      ROUTES.entryTrash(local.entryId),
      ROUTES.entryRestore(local.entryId),
    ]);
  });

  it('reports a 404 as a failure for the driver to classify', async () => {
    expect(await run('trashEntry')).toEqual({
      failure: { kind: 'http', status: 404, code: 'NOT_FOUND', retryAfterSeconds: null },
    });
  });
});

describe('deleteEntry', () => {
  it('deletes on the server, then removes every local row, file and outbox item of the entry', async () => {
    fakeHttp.on('DELETE', ROUTES.entry(local.entryId), () => undefined);
    const files = [...local.originalObjectIds, ...local.thumbnailObjectIds];
    expect(await run('deleteEntry')).toBe('done');
    const state = await fixture.rowStore.transaction(async (tx) => ({
      entry: await tx.entries.get(local.entryId),
      media: await tx.media.byEntry(local.entryId),
      memberships: await tx.memberships.byEntry(local.entryId),
      objects: await tx.objects.all(),
      outbox: await tx.outbox.all(),
    }));
    expect(state.entry).toBeNull();
    expect(state.media).toEqual([]);
    expect(state.memberships).toEqual([]);
    expect(state.objects).toEqual([]);
    expect(state.outbox).toEqual([]);
    for (const objectId of files) expect(await fixture.fileStore.size(objectId)).toBeNull();
    expect(changed).toContain(local.entryId);
  });

  it('keeps local data when the server call fails', async () => {
    fakeHttp.on('DELETE', ROUTES.entry(local.entryId), () => {
      throw new NetworkError();
    });
    expect(await run('deleteEntry')).toEqual({ failure: { kind: 'network' } });
    expect(await entryRow()).toBeTruthy();
  });
});

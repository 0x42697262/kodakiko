import type { EntryMetadataV1, ErrorCode, Id, MediaDescriptorV1, OutboxKind } from '@kodakiko/contract';
import type { z } from 'zod';
import {
  classifyPushFailure,
  decidePulledEntry,
  mergeMetadata,
  metadataEquivalent,
  nextAttemptAt,
  planCollections,
  planPushBatch,
  type LocalCollectionState,
  type LocalEntryState,
  type OutboxItemView,
  type PushFailure,
  type RemoteCollectionState,
} from '../../src/sync/index.ts';
import { functionCase, outcome, toJson, type GoldenCase, type GoldenWriter } from './shared.ts';

type Metadata = z.infer<typeof EntryMetadataV1>;
type Descriptor = z.infer<typeof MediaDescriptorV1>;
type Patch = Partial<Metadata>;

const PATHS = Object.freeze({
  failures: 'sync/failures.json',
  pushPlan: 'sync/push-plan.json',
  pulledEntry: 'sync/pulled-entry.json',
  collectionsPlan: 'sync/collections-plan.json',
  merge: 'sync/merge.json',
});

const RANDOM_EXHAUSTED = 'golden: random sequence exhausted';

type SequenceRandom = { readonly random: () => number; readonly calls: () => number };

function sequenceRandom(values: readonly number[]): SequenceRandom {
  let calls = 0;
  return {
    random: () => {
      const value = values[calls];
      if (value === undefined) throw new Error(RANDOM_EXHAUSTED);
      calls += 1;
      return value;
    },
    calls: () => calls,
  };
}

const FAILURE_NOW = 5_000_000;
const HALF = [0.5];

type NextRow = { readonly name: string; readonly attempts: number; readonly now: number; readonly randomValues: readonly number[] };

function nextAttemptCase(row: NextRow): GoldenCase {
  const rng = sequenceRandom(row.randomValues);
  const args = { attempts: row.attempts, now: row.now, randomValues: [...row.randomValues] };
  const result = outcome(() => nextAttemptAt(row.attempts, row.now, rng.random));
  return { name: `nextAttemptAt: ${row.name}`, fn: 'nextAttemptAt', args, result, randomCalls: rng.calls() };
}

type ClassifyRow = {
  readonly name: string;
  readonly kind: OutboxKind;
  readonly failure: PushFailure;
  readonly attempts: number;
  readonly now?: number;
  readonly randomValues?: readonly number[];
};

function classifyCase(row: ClassifyRow): GoldenCase {
  const now = row.now ?? FAILURE_NOW;
  const randomValues = row.randomValues ?? HALF;
  const rng = sequenceRandom(randomValues);
  const args = { kind: row.kind, failure: toJson(row.failure), attempts: row.attempts, now, randomValues: [...randomValues] };
  const result = outcome(() => classifyPushFailure(row.kind, row.failure, row.attempts, now, rng.random));
  return { name: `classifyPushFailure: ${row.name}`, fn: 'classifyPushFailure', args, result, randomCalls: rng.calls() };
}

function http(status: number, code: ErrorCode, retryAfterSeconds: number | null = null): PushFailure {
  return { kind: 'http', status, code, retryAfterSeconds };
}

const NETWORK: PushFailure = { kind: 'network' };

const ALL_KINDS: readonly OutboxKind[] = [
  'createEntry',
  'updateEntryMeta',
  'addMedia',
  'removeMedia',
  'trashEntry',
  'restoreEntry',
  'deleteEntry',
];

function failureCases(): GoldenCase[] {
  const next: NextRow[] = [
    { name: 'multiplies the random fraction by the exponential ceiling (attempts 0)', attempts: 0, now: FAILURE_NOW, randomValues: HALF },
    { name: 'multiplies the random fraction by the exponential ceiling (attempts 3)', attempts: 3, now: FAILURE_NOW, randomValues: HALF },
    { name: 'multiplies the random fraction by the exponential ceiling (random 0)', attempts: 2, now: FAILURE_NOW, randomValues: [0] },
    { name: 'caps the ceiling', attempts: 20, now: FAILURE_NOW, randomValues: [1] },
    { name: 'edge: attempts 8 is still under the cap', attempts: 8, now: FAILURE_NOW, randomValues: [1] },
    { name: 'edge: attempts 9 hits the cap', attempts: 9, now: FAILURE_NOW, randomValues: [1] },
    { name: 'edge: a fraction is not rounded', attempts: 0, now: FAILURE_NOW, randomValues: [0.123456789] },
    { name: 'edge: an inexact double product is not rounded', attempts: 9, now: 0, randomValues: [0.29] },
    { name: 'edge: the double sum rounds the inexact product', attempts: 9, now: FAILURE_NOW, randomValues: [0.29] },
    { name: 'edge: a large now rounds as a double sum', attempts: 9, now: 1_700_000_000_000, randomValues: [0.123456789] },
    { name: 'edge: a huge attempt count overflows to the cap', attempts: 2000, now: FAILURE_NOW, randomValues: [0.75] },
    { name: 'edge: a negative attempt count halves the base', attempts: -1, now: FAILURE_NOW, randomValues: [0.7] },
    { name: 'edge: only the first random value is consumed', attempts: 1, now: 0, randomValues: [0.25, 0.9] },
  ];
  const classify: ClassifyRow[] = [
    { name: 'retries network failures with backoff', kind: 'createEntry', failure: NETWORK, attempts: 2 },
    { name: 'retries 5xx and STORAGE_UNAVAILABLE with backoff (503 INTERNAL)', kind: 'addMedia', failure: http(503, 'INTERNAL'), attempts: 1 },
    { name: 'retries 5xx and STORAGE_UNAVAILABLE with backoff (502 STORAGE_UNAVAILABLE)', kind: 'addMedia', failure: http(502, 'STORAGE_UNAVAILABLE'), attempts: 1 },
    { name: 'goes dead once attempts reach MAX_ATTEMPTS (network)', kind: 'createEntry', failure: NETWORK, attempts: 8 },
    { name: 'goes dead once attempts reach MAX_ATTEMPTS (500)', kind: 'createEntry', failure: http(500, 'INTERNAL'), attempts: 8 },
    { name: 'goes dead once attempts reach MAX_ATTEMPTS (network one below retries)', kind: 'createEntry', failure: NETWORK, attempts: 7 },
    { name: 'waits for Retry-After on 429', kind: 'createEntry', failure: http(429, 'RATE_LIMITED', 30), attempts: 1 },
    { name: 'falls back to backoff on 429 without Retry-After', kind: 'createEntry', failure: http(429, 'RATE_LIMITED'), attempts: 1 },
    { name: 'never dead-letters a 429, even beyond MAX_ATTEMPTS (Retry-After 5)', kind: 'createEntry', failure: http(429, 'RATE_LIMITED', 5), attempts: 11 },
    { name: 'never dead-letters a 429, even beyond MAX_ATTEMPTS (no Retry-After)', kind: 'createEntry', failure: http(429, 'RATE_LIMITED'), attempts: 11 },
    { name: 'maps REVISION_CONFLICT to conflict', kind: 'updateEntryMeta', failure: http(409, 'REVISION_CONFLICT'), attempts: 0 },
    ...(['updateEntryMeta', 'trashEntry', 'restoreEntry', 'removeMedia', 'deleteEntry'] as const).map((kind) => ({
      name: `drops 404 for update-like kinds (${kind})`,
      kind,
      failure: http(404, 'NOT_FOUND'),
      attempts: 0,
    })),
    ...(['createEntry', 'addMedia'] as const).map((kind) => ({
      name: `dead-letters 404 for createEntry and addMedia (${kind})`,
      kind,
      failure: http(404, 'NOT_FOUND'),
      attempts: 0,
    })),
    ...ALL_KINDS.map((kind) => ({ name: `signs out on 401 (${kind})`, kind, failure: http(401, 'UNAUTHENTICATED'), attempts: 0 })),
    ...(
      [
        [403, 'QUOTA_EXCEEDED'],
        [403, 'FEATURE_DISABLED'],
        [413, 'PAYLOAD_TOO_LARGE'],
        [422, 'VALIDATION_FAILED'],
        [409, 'ID_CONFLICT'],
        [400, 'MALFORMED_REQUEST'],
      ] as const
    ).map(([status, code]) => ({
      name: `dead-letters quota, disabled feature, 413, 422 and other 4xx immediately (${status} ${code})`,
      kind: 'createEntry' as const,
      failure: http(status, code),
      attempts: 0,
    })),
    { name: 'edge: 401 wins over REVISION_CONFLICT', kind: 'updateEntryMeta', failure: http(401, 'REVISION_CONFLICT'), attempts: 0 },
    { name: 'edge: REVISION_CONFLICT wins over a 5xx status', kind: 'updateEntryMeta', failure: http(500, 'REVISION_CONFLICT'), attempts: 0 },
    { name: 'edge: REVISION_CONFLICT wins over 404', kind: 'createEntry', failure: http(404, 'REVISION_CONFLICT'), attempts: 0 },
    { name: 'edge: 404 is decided by status whatever the code', kind: 'trashEntry', failure: http(404, 'INTERNAL'), attempts: 0 },
    { name: 'edge: 429 is decided by status whatever the code', kind: 'createEntry', failure: http(429, 'INTERNAL', 2), attempts: 0 },
    { name: 'edge: RATE_LIMITED with status 503 uses backoff', kind: 'createEntry', failure: http(503, 'RATE_LIMITED', 30), attempts: 2 },
    { name: 'edge: STORAGE_UNAVAILABLE with a 4xx status retries', kind: 'addMedia', failure: http(400, 'STORAGE_UNAVAILABLE'), attempts: 3 },
    { name: 'edge: STORAGE_UNAVAILABLE at MAX_ATTEMPTS is dead', kind: 'addMedia', failure: http(400, 'STORAGE_UNAVAILABLE'), attempts: 8 },
    { name: 'edge: a dead 5xx still consumes one random value', kind: 'createEntry', failure: http(599, 'INTERNAL'), attempts: 9, randomValues: [0.3] },
    { name: 'edge: status 499 is dead', kind: 'createEntry', failure: http(499, 'INTERNAL'), attempts: 0 },
    { name: 'edge: status 500 with a fractional backoff', kind: 'removeMedia', failure: http(500, 'INTERNAL'), attempts: 4, now: 1_700_000_000_000, randomValues: [0.123456789] },
    { name: 'edge: Retry-After 0 retries now', kind: 'createEntry', failure: http(429, 'RATE_LIMITED', 0), attempts: 0 },
    { name: 'edge: fractional Retry-After is not rounded', kind: 'createEntry', failure: http(429, 'RATE_LIMITED', 1.5), attempts: 0 },
    { name: 'edge: a network failure ignores the outbox kind', kind: 'deleteEntry', failure: NETWORK, attempts: 0, randomValues: [0.999] },
    { name: 'edge: a network failure with a huge attempt count is dead', kind: 'createEntry', failure: NETWORK, attempts: 2000, randomValues: [0.75] },
  ];
  return [...next.map(nextAttemptCase), ...classify.map(classifyCase)];
}

const PLAN_NOW = 1_000;
const DEFAULT_CAPACITY = 10;

function item(id: number, entryId: string | null, overrides: Partial<OutboxItemView> = {}): OutboxItemView {
  return { id, kind: 'createEntry', entryId, state: 'queued', nextAttemptAt: 0, ...overrides };
}

type PushRow = {
  readonly name: string;
  readonly items: readonly OutboxItemView[];
  readonly inFlight?: readonly string[];
  readonly now?: number;
  readonly capacity?: number;
};

function pushCase(row: PushRow): GoldenCase {
  const inFlight = new Set(row.inFlight ?? []);
  const now = row.now ?? PLAN_NOW;
  const capacity = row.capacity ?? DEFAULT_CAPACITY;
  const args = { items: toJson(row.items), inFlightEntryIds: toJson(inFlight), now, capacity };
  return functionCase(`planPushBatch: ${row.name}`, 'planPushBatch', args, () => planPushBatch(row.items, inFlight, now, capacity));
}

function pushPlanCases(): GoldenCase[] {
  const three = [item(1, 'a'), item(2, 'b'), item(3, 'c')];
  const rows: PushRow[] = [
    { name: 'returns due queued items in ascending id order', items: [item(3, 'c'), item(1, 'a'), item(2, 'b')] },
    { name: 'skips items that are not due yet', items: [item(1, 'a', { nextAttemptAt: 1001 }), item(2, 'b', { nextAttemptAt: 1000 })] },
    { name: 'skips dead items', items: [item(1, 'a', { state: 'dead' }), item(2, 'b')] },
    {
      name: 'blocks a later item of the same entry while the earlier one is queued',
      items: [item(1, 'a'), item(2, 'a', { kind: 'updateEntryMeta' }), item(3, 'b')],
    },
    {
      name: 'blocks a later item of the same entry while the earlier one is dead',
      items: [item(1, 'a', { state: 'dead' }), item(2, 'a', { kind: 'updateEntryMeta' })],
    },
    {
      name: 'blocks a later item of the same entry while the earlier one is not due yet',
      items: [item(1, 'a', { nextAttemptAt: 1005 }), item(2, 'a', { kind: 'trashEntry' })],
    },
    { name: 'skips entries that are in flight', items: [item(1, 'a'), item(2, 'b')], inFlight: ['a'] },
    { name: 'never blocks items without an entry id on each other', items: [item(1, null), item(2, null)] },
    { name: 'returns at most capacity ids (capacity 2)', items: three, capacity: 2 },
    { name: 'returns at most capacity ids (capacity 0)', items: three, capacity: 0 },
    { name: 'does not mutate its input', items: [item(2, 'b'), item(1, 'a')] },
    { name: 'edge: an empty outbox', items: [] },
    {
      name: 'edge: order is by id, not by input position, when blocking',
      items: [item(5, 'a', { kind: 'trashEntry' }), item(2, 'a', { nextAttemptAt: 2000 })],
    },
    {
      name: 'edge: an in-flight entry blocks all its items',
      items: [item(1, 'a'), item(2, 'a', { kind: 'updateEntryMeta' }), item(3, null), item(4, 'b')],
      inFlight: ['a', 'z'],
    },
    {
      name: 'edge: items over capacity still block later items of their entry',
      items: [item(1, 'a'), item(2, 'b'), item(3, 'a', { kind: 'updateEntryMeta' }), item(4, 'c')],
      capacity: 1,
    },
    { name: 'edge: a negative capacity selects nothing', items: three, capacity: -1 },
    {
      name: 'edge: nextAttemptAt equal to now is due and fractional values compare exactly',
      items: [item(1, 'a', { nextAttemptAt: 1000 }), item(2, 'b', { nextAttemptAt: 1000.0000001 }), item(3, 'c', { nextAttemptAt: 999.5 })],
    },
    {
      name: 'edge: null entry ids are never blocked and never in flight',
      items: [item(1, null, { state: 'dead' }), item(2, null, { kind: 'deleteEntry' }), item(3, null, { nextAttemptAt: 5000 })],
      inFlight: [''],
    },
    {
      name: 'edge: an empty-string entry id is a normal entry id',
      items: [item(1, ''), item(2, '', { kind: 'updateEntryMeta' })],
    },
    {
      name: 'edge: duplicate ids keep their input order (stable sort)',
      items: [item(7, 'a', { nextAttemptAt: 5000 }), item(3, 'c'), item(7, 'a', { kind: 'updateEntryMeta' })],
    },
    {
      name: 'edge: a mixed outbox',
      items: [
        item(9, 'd', { kind: 'addMedia' }),
        item(4, 'b', { kind: 'updateEntryMeta', nextAttemptAt: 1500 }),
        item(6, 'b', { kind: 'trashEntry' }),
        item(1, 'a', { state: 'dead' }),
        item(8, null, { kind: 'deleteEntry' }),
        item(2, 'c'),
        item(3, 'a', { kind: 'updateEntryMeta' }),
        item(10, 'e', { kind: 'removeMedia' }),
        item(5, 'f', { kind: 'restoreEntry' }),
      ],
      inFlight: ['d'],
      capacity: 3,
    },
  ];
  return rows.map(pushCase);
}

function pulledCase(name: string, local: LocalEntryState, pulledRevision: number): GoldenCase {
  return functionCase(`decidePulledEntry: ${name}`, 'decidePulledEntry', { local, pulledRevision }, () => decidePulledEntry(local, pulledRevision));
}

function pulledEntryCases(): GoldenCase[] {
  return [
    pulledCase('inserts an entry that is absent locally', null, 1),
    pulledCase('overwrites a synced entry when pulled >= base (equal)', { localState: 'synced', baseRevision: 3 }, 3),
    pulledCase('overwrites a synced entry when pulled >= base (greater)', { localState: 'synced', baseRevision: 3 }, 4),
    pulledCase('keeps the pending change when pulled >= base (greater)', { localState: 'pending_update', baseRevision: 3 }, 4),
    pulledCase('keeps the pending change when pulled >= base (equal)', { localState: 'pending_update', baseRevision: 3 }, 3),
    pulledCase('keeps a pending create when any revision is pulled', { localState: 'pending_create', baseRevision: 0 }, 1),
    ...(['synced', 'pending_update', 'pending_create'] as const).map((localState) =>
      pulledCase(`rejects a rollback for every local state (${localState})`, { localState, baseRevision: 5 }, 4),
    ),
    pulledCase('edge: absent locally with revision 0', null, 0),
    pulledCase('edge: synced at revision 0 pulled 0', { localState: 'synced', baseRevision: 0 }, 0),
    pulledCase('edge: pending create pulled 0', { localState: 'pending_create', baseRevision: 0 }, 0),
    pulledCase('edge: a large revision jump on a synced entry', { localState: 'synced', baseRevision: 1 }, 1_000_000),
    pulledCase('edge: a rollback by one on a large revision', { localState: 'pending_update', baseRevision: 1_000_000 }, 999_999),
  ];
}

const META = '2026-11-12T10:00:00.000Z';
const META_NEW = '2026-11-13T10:00:00.000Z';

function remote(id: string, overrides: Partial<RemoteCollectionState> = {}): RemoteCollectionState {
  return { id, kind: 'home', role: 'owner', lastSeq: 0, tombstonesPrunedThroughSeq: 0, metaUpdatedAt: META, ...overrides };
}

function local(id: string, overrides: Partial<LocalCollectionState> = {}): LocalCollectionState {
  return { id, cursorSeq: 0, metaUpdatedAt: META, ...overrides };
}

function collectionsCase(name: string, remoteStates: readonly RemoteCollectionState[], localStates: readonly LocalCollectionState[]): GoldenCase {
  const args = { remote: toJson(remoteStates), local: toJson(localStates) };
  return functionCase(`planCollections: ${name}`, 'planCollections', args, () => planCollections(remoteStates, localStates));
}

function collectionsPlanCases(): GoldenCase[] {
  const pruned = { lastSeq: 20, tombstonesPrunedThroughSeq: 10 };
  return [
    collectionsCase('does nothing when everything is current', [remote('a', { lastSeq: 4 })], [local('a', { cursorSeq: 4 })]),
    collectionsCase('ignores remote link collections entirely', [remote('l', { kind: 'link', lastSeq: 9 })], []),
    collectionsCase('refetches and pulls a remote collection that is absent locally', [remote('a', { lastSeq: 3 })], []),
    collectionsCase('refetches but does not pull a new empty collection', [remote('a', { lastSeq: 0 })], []),
    collectionsCase('refetches when metaUpdatedAt differs', [remote('a', { metaUpdatedAt: META_NEW })], [local('a')]),
    collectionsCase('drops local collections that are absent remotely', [remote('a')], [local('a'), local('gone')]),
    collectionsCase('drops a local collection whose remote counterpart is a link collection', [remote('x', { kind: 'link' })], [local('x')]),
    collectionsCase('pulls when lastSeq is ahead of the cursor', [remote('a', { lastSeq: 8 })], [local('a', { cursorSeq: 5 })]),
    collectionsCase('resyncs when 0 < cursor < tombstonesPrunedThroughSeq', [remote('a', pruned)], [local('a', { cursorSeq: 4 })]),
    collectionsCase('never resyncs a cursor of 0', [remote('a', pruned)], [local('a', { cursorSeq: 0 })]),
    collectionsCase('does not resync a cursor equal to the pruned seq', [remote('a', pruned)], [local('a', { cursorSeq: 10 })]),
    collectionsCase('keeps remote order in the result lists', [remote('b', { lastSeq: 1 }), remote('a', { lastSeq: 1 })], []),
    collectionsCase('edge: everything empty', [], []),
    collectionsCase('edge: a cursor ahead of lastSeq does not pull', [remote('a', { lastSeq: 3 })], [local('a', { cursorSeq: 7 })]),
    collectionsCase(
      'edge: refetch and resync are independent',
      [remote('a', { ...pruned, metaUpdatedAt: META_NEW })],
      [local('a', { cursorSeq: 1 })],
    ),
    collectionsCase(
      'edge: an unknown collection is never resynced, even with pruned tombstones',
      [remote('a', pruned), remote('b', { lastSeq: 0, tombstonesPrunedThroughSeq: 5 })],
      [],
    ),
    collectionsCase(
      'edge: metaUpdatedAt is compared as a string, not as an instant',
      [remote('a', { metaUpdatedAt: '2026-11-12T10:00:00Z' })],
      [local('a')],
    ),
    collectionsCase(
      'edge: shared collections behave like home collections whatever the role',
      [remote('s', { kind: 'shared', role: 'viewer', lastSeq: 2 }), remote('o', { kind: 'shared', role: 'owner', lastSeq: 1 })],
      [local('s', { cursorSeq: 1 })],
    ),
    collectionsCase(
      'edge: drop keeps local order',
      [remote('m')],
      [local('z'), local('m'), local('b'), local('a')],
    ),
    collectionsCase(
      'edge: a mixed plan',
      [
        remote('home', { lastSeq: 12, metaUpdatedAt: META_NEW }),
        remote('link1', { kind: 'link', lastSeq: 4 }),
        remote('new', { kind: 'shared', role: 'viewer', lastSeq: 2 }),
        remote('old', { kind: 'shared', lastSeq: 40, tombstonesPrunedThroughSeq: 30 }),
        remote('same', { lastSeq: 6 }),
      ],
      [
        local('same', { cursorSeq: 6 }),
        local('link1', { cursorSeq: 4 }),
        local('old', { cursorSeq: 29 }),
        local('home', { cursorSeq: 11 }),
        local('removed', { cursorSeq: 3 }),
      ],
    ),
  ];
}

const CREATED = '2026-01-01T00:00:00.000Z';
const OLDER = '2026-02-01T00:00:00.000Z';
const NEWER = '2026-03-01T00:00:00.000Z';
const MERGE_NOW = '2026-04-01T00:00:00.000Z';
const TAKEN = '2026-01-05T10:00:00.000Z';
const TAKEN_LOCAL = '2026-01-06T10:00:00.000Z';
const TAKEN_SERVER = '2026-01-07T10:00:00.000Z';

const ID_ONE = 'mmmmmmmmmmmmmmmmmmmm01' as Id;
const ID_TWO = 'mmmmmmmmmmmmmmmmmmmm02' as Id;
const ID_THREE = 'mmmmmmmmmmmmmmmmmmmm03' as Id;
const ID_FOUR = 'mmmmmmmmmmmmmmmmmmmm04' as Id;

const PLACE = { latitude: 48.85, longitude: 2.35, accuracyMeters: 10, source: 'device' } as const;
const OTHER_PLACE = { latitude: 52.5, longitude: 13.4, accuracyMeters: null, source: 'exif' } as const;
const LATITUDE_NUDGE = 0.000001;
const TAG_LIMIT_PLUS_ONE = 65;
const EMOJI_AT_LIMIT = 32;

function descriptor(mediaId: Id, overrides: Partial<Descriptor> = {}): Descriptor {
  return {
    mediaId,
    kind: 'photo',
    mimeType: 'image/jpeg',
    width: 100,
    height: 80,
    durationMs: null,
    originalPlaintextBytes: 1000,
    thumbnail: { width: 10, height: 8, mimeType: 'image/webp', plaintextBytes: 64 },
    capturedAtUtc: null,
    originalFileName: null,
    ...overrides,
  };
}

function captioned(caption: string | undefined, mediaId: Id = ID_ONE): Descriptor {
  return caption === undefined ? descriptor(mediaId) : { ...descriptor(mediaId), caption };
}

function metadata(patch: Patch = {}): Metadata {
  return {
    schemaVersion: 1,
    takenAtUtc: TAKEN,
    utcOffsetMinutes: 0,
    timeZone: null,
    note: 'base',
    tags: ['a', 'b'],
    location: null,
    media: [descriptor(ID_ONE), descriptor(ID_TWO)],
    createdAtUtc: CREATED,
    editedAtUtc: CREATED,
    ...patch,
  };
}

type MergeRow = {
  readonly name: string;
  readonly ancestor?: Patch;
  readonly local?: Patch;
  readonly server?: Patch;
  readonly nowUtc?: string;
};

function mergeCaseOf(name: string, ancestor: Metadata, localSide: Metadata, server: Metadata, nowUtc: string = MERGE_NOW): GoldenCase {
  const args = { ancestor: toJson(ancestor), local: toJson(localSide), server: toJson(server), nowUtc };
  return functionCase(`mergeMetadata: ${name}`, 'mergeMetadata', args, () => mergeMetadata(ancestor, localSide, server, nowUtc));
}

function mergeRowCase(group: string, row: MergeRow): GoldenCase {
  return mergeCaseOf(
    `${group}: ${row.name}`,
    metadata(row.ancestor),
    metadata({ ...row.ancestor, ...row.local }),
    metadata({ ...row.ancestor, ...row.server }),
    row.nowUtc,
  );
}

function equivalentCase(name: string, left: Metadata, right: Metadata): GoldenCase {
  const args = { left: toJson(left), right: toJson(right) };
  return functionCase(`metadataEquivalent: ${name}`, 'metadataEquivalent', args, () => metadataEquivalent(left, right));
}

const NOTE_ROWS: readonly MergeRow[] = [
  { name: 'note unchanged on both sides keeps the ancestor note' },
  { name: 'note changed only locally takes the local note', local: { note: 'mine', editedAtUtc: NEWER }, server: { editedAtUtc: OLDER } },
  {
    name: 'note changed only on the server takes the server note even when local edited later',
    local: { editedAtUtc: NEWER },
    server: { note: 'theirs', editedAtUtc: OLDER },
  },
  {
    name: 'note changed on both sides takes the local note when local edited later',
    local: { note: 'mine', editedAtUtc: NEWER },
    server: { note: 'theirs', editedAtUtc: OLDER },
  },
  {
    name: 'note changed on both sides takes the server note when the server edited later',
    local: { note: 'mine', editedAtUtc: OLDER },
    server: { note: 'theirs', editedAtUtc: NEWER },
  },
  {
    name: 'note changed on both sides with equal edit times takes the local note',
    local: { note: 'mine', editedAtUtc: OLDER },
    server: { note: 'theirs', editedAtUtc: OLDER },
  },
  {
    name: 'note changed to the same text on both sides keeps that text',
    local: { note: 'same', editedAtUtc: OLDER },
    server: { note: 'same', editedAtUtc: NEWER },
  },
];

const TAG_ROWS: readonly MergeRow[] = [
  { name: 'tags unchanged stay the same' },
  { name: 'a tag added locally is kept', local: { tags: ['a', 'b', 'c'] } },
  { name: 'a tag added on the server is kept', server: { tags: ['a', 'b', 'c'] } },
  { name: 'different tags added on both sides are united', local: { tags: ['a', 'b', 'c'] }, server: { tags: ['a', 'b', 'd'] } },
  { name: 'a tag removed locally stays removed', local: { tags: ['a'] } },
  { name: 'a tag removed on the server stays removed', server: { tags: ['b'] } },
  { name: 'different tags removed on both sides are both removed', local: { tags: ['a'] }, server: { tags: ['b'] } },
  { name: 'a removal on one side and an addition on the other both apply', local: { tags: ['a'] }, server: { tags: ['a', 'b', 'c'] } },
  { name: 'the same tag added on both sides appears once', local: { tags: ['a', 'b', 'c'] }, server: { tags: ['a', 'b', 'c'] } },
  { name: 'tags are normalized before comparison', local: { tags: ['a', 'b', '  Family '] }, server: { tags: ['a', 'b', 'family'] } },
  {
    name: 'a tag removed on one side stays removed when the other side only changes its case',
    local: { tags: ['a'] },
    server: { tags: ['A', 'b'] },
  },
  { name: 'the result is sorted', local: { tags: ['a', 'b', 'zeta'] }, server: { tags: ['a', 'b', 'alpha'] } },
  { name: 'tags that normalize to nothing are dropped', local: { tags: ['a', 'b', '   '] } },
  {
    name: 'edge: sorting is by UTF-16 code units',
    local: { tags: ['a', 'b', 'Zebra', 'éclair', 'émile', 'z'] },
    server: { tags: ['a', 'b', '😀 smile', '\uFF5E tilde', 'ä', '10', '9', 'B2'] },
  },
  {
    name: 'edge: NFKC, whitespace runs and non-ASCII lowercasing',
    local: { tags: ['a', 'b', '\uFB01sh', 'Ｆｕｌｌ', 'ÉCOLE', 'İstanbul', 'ΣΟΦΙΑ', 'ΟΔΟΣ'] },
    server: { tags: ['a', 'b', ' new\t\u00A0\u3000york ', 'STRASSE', 'Straße'] },
  },
  {
    name: 'edge: trim and whitespace runs use the JS whitespace set',
    local: { tags: ['a', 'b', '\uFEFFbom\uFEFF', 'line\u2028sep', 'zero\u200Bwidth'] },
    server: { tags: ['a', 'b', '\u2029para\u2003em\u202F\u205Fnarrow\u1680', '\u0085next'] },
  },
  {
    name: 'edge: tags over 64 UTF-16 code units are dropped',
    local: { tags: ['a', 'b', 'x'.repeat(TAG_LIMIT_PLUS_ONE), '😀'.repeat(EMOJI_AT_LIMIT)] },
    server: { tags: ['a', 'b', '😀'.repeat(EMOJI_AT_LIMIT + 1), `  ${'y'.repeat(TAG_LIMIT_PLUS_ONE - 1)}  `] },
  },
  {
    name: 'edge: an un-normalized ancestor tag matches its normalized form',
    ancestor: { tags: ['  Trip  ', 'b'] },
    local: { tags: ['trip', 'b'] },
    server: { tags: ['TRIP', 'b', 'new'] },
  },
  { name: 'edge: duplicate tags collapse', local: { tags: ['a', 'b', 'c', 'C', ' c '] }, server: { tags: ['a', 'a', 'b'] } },
];

const TIME_ROWS: readonly MergeRow[] = [
  { name: 'time unchanged keeps the ancestor time' },
  {
    name: 'time changed only locally takes the whole local unit',
    local: { takenAtUtc: TAKEN_LOCAL, utcOffsetMinutes: 60, timeZone: 'Europe/Paris' },
  },
  {
    name: 'time changed only on the server takes the whole server unit',
    server: { takenAtUtc: TAKEN_SERVER, utcOffsetMinutes: 120, timeZone: 'Europe/Athens' },
  },
  {
    name: 'time changed on both sides takes the local unit when local edited later',
    local: { takenAtUtc: TAKEN_LOCAL, utcOffsetMinutes: 60, editedAtUtc: NEWER },
    server: { takenAtUtc: TAKEN_SERVER, timeZone: 'Europe/Athens', editedAtUtc: OLDER },
  },
  {
    name: 'time changed on both sides takes the server unit when the server edited later',
    local: { takenAtUtc: TAKEN_LOCAL, utcOffsetMinutes: 60, editedAtUtc: OLDER },
    server: { takenAtUtc: TAKEN_SERVER, timeZone: 'Europe/Athens', editedAtUtc: NEWER },
  },
  { name: 'a change of only the time zone moves the whole unit', server: { timeZone: 'Europe/Athens' } },
  {
    name: 'edge: edit times are compared as instants, not as strings',
    local: { takenAtUtc: TAKEN_LOCAL, editedAtUtc: '2026-03-01T00:30:00.000Z' },
    server: { takenAtUtc: TAKEN_SERVER, editedAtUtc: '2026-03-01T01:00:00+01:00' },
  },
  {
    name: 'edge: the same instant written differently is a tie and local wins',
    local: { takenAtUtc: TAKEN_LOCAL, editedAtUtc: '2026-03-01T00:00:00Z' },
    server: { takenAtUtc: TAKEN_SERVER, editedAtUtc: NEWER },
  },
  {
    name: 'edge: milliseconds decide',
    local: { takenAtUtc: TAKEN_LOCAL, editedAtUtc: '2026-03-01T00:00:00.001Z' },
    server: { takenAtUtc: TAKEN_SERVER, editedAtUtc: '2026-03-01T00:00:00.002Z' },
  },
  {
    name: 'edge: an unparsable server edit time never wins',
    local: { takenAtUtc: TAKEN_LOCAL, editedAtUtc: OLDER },
    server: { takenAtUtc: TAKEN_SERVER, editedAtUtc: 'not a date' },
  },
  {
    name: 'edge: an unparsable local edit time never loses by comparison',
    local: { takenAtUtc: TAKEN_LOCAL, editedAtUtc: 'not a date' },
    server: { takenAtUtc: TAKEN_SERVER, editedAtUtc: NEWER },
  },
  {
    name: 'edge: changing a field back to the ancestor value is no change',
    local: { utcOffsetMinutes: 0, editedAtUtc: NEWER },
    server: { utcOffsetMinutes: -300, timeZone: 'America/New_York', editedAtUtc: OLDER },
  },
];

const LOCATION_ROWS: readonly MergeRow[] = [
  { name: 'location unchanged stays empty' },
  { name: 'location set only locally is kept', local: { location: PLACE } },
  { name: 'location set only on the server is kept', server: { location: PLACE } },
  {
    name: 'location changed on both sides takes the server place when the server edited later',
    local: { location: PLACE, editedAtUtc: OLDER },
    server: { location: OTHER_PLACE, editedAtUtc: NEWER },
  },
  {
    name: 'location changed on both sides takes the local place when local edited later',
    local: { location: PLACE, editedAtUtc: NEWER },
    server: { location: OTHER_PLACE, editedAtUtc: OLDER },
  },
  {
    name: 'location changed on both sides with equal times takes the local place',
    local: { location: PLACE, editedAtUtc: OLDER },
    server: { location: OTHER_PLACE, editedAtUtc: OLDER },
  },
  { name: 'location cleared locally stays cleared', ancestor: { location: PLACE }, local: { location: null } },
  {
    name: 'edge: location cleared on the server wins over an older local move',
    ancestor: { location: PLACE },
    local: { location: OTHER_PLACE, editedAtUtc: OLDER },
    server: { location: null, editedAtUtc: NEWER },
  },
  {
    name: 'edge: a location equal field by field is unchanged',
    ancestor: { location: PLACE },
    local: { location: { ...PLACE } },
    server: { location: { ...PLACE, accuracyMeters: null } },
  },
  {
    name: 'edge: the location and the note are decided separately',
    local: { location: PLACE, note: 'mine', editedAtUtc: OLDER },
    server: { location: OTHER_PLACE, editedAtUtc: NEWER },
  },
];

const MEDIA_ROWS: readonly MergeRow[] = [
  { name: 'media unchanged stays the same' },
  { name: 'media removed locally stays removed', local: { media: [descriptor(ID_ONE)] } },
  { name: 'media removed on the server stays removed', server: { media: [descriptor(ID_TWO)] } },
  {
    name: 'different media removed on both sides are both removed',
    ancestor: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_THREE)] },
    local: { media: [descriptor(ID_TWO), descriptor(ID_THREE)] },
    server: { media: [descriptor(ID_ONE), descriptor(ID_THREE)] },
  },
  {
    name: 'media added on the server is appended after the local order',
    server: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_THREE)] },
  },
  {
    name: 'media added on both sides keeps local additions in place and appends server additions',
    local: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_FOUR)] },
    server: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_THREE)] },
  },
  {
    name: 'several server additions keep the server order',
    server: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_FOUR), descriptor(ID_THREE)] },
  },
  { name: 'media reordered locally keeps the local order', local: { media: [descriptor(ID_TWO), descriptor(ID_ONE)] } },
  {
    name: 'a descriptor that differs on both sides takes the server version',
    local: { media: [descriptor(ID_ONE, { width: 100 }), descriptor(ID_TWO)] },
    server: { media: [descriptor(ID_ONE, { width: 200 }), descriptor(ID_TWO)] },
  },
  {
    name: 'media removed locally stays removed even when the server edited its descriptor',
    local: { media: [descriptor(ID_ONE)] },
    server: { media: [descriptor(ID_ONE), descriptor(ID_TWO, { width: 300 })] },
  },
  {
    name: 'media removed on the server is dropped even when local still holds it',
    local: { media: [descriptor(ID_ONE, {}), descriptor(ID_TWO, { width: 150 })] },
    server: { media: [descriptor(ID_ONE)] },
  },
  {
    name: 'edge: a descriptor changed only locally is replaced by the server version',
    local: { media: [descriptor(ID_ONE, { originalFileName: 'local.jpg' }), descriptor(ID_TWO)] },
  },
  {
    name: 'edge: the same new media added on both sides appears once with the server descriptor',
    local: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_THREE, { width: 111 })] },
    server: { media: [descriptor(ID_THREE, { width: 222 }), descriptor(ID_ONE), descriptor(ID_TWO)] },
  },
  {
    name: 'edge: everything removed on both sides gives an empty list',
    local: { media: [descriptor(ID_ONE)] },
    server: { media: [descriptor(ID_TWO)] },
  },
  {
    name: 'edge: server reordering is ignored',
    local: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_THREE)] },
    server: { media: [descriptor(ID_TWO), descriptor(ID_FOUR), descriptor(ID_ONE)] },
  },
];

const CAPTION_ROWS: readonly MergeRow[] = [
  {
    name: 'edge: captions changed differently on both sides take the local caption whatever the edit times',
    ancestor: { media: [captioned('old')] },
    local: { media: [captioned('mine')], editedAtUtc: OLDER },
    server: { media: [captioned('theirs')], editedAtUtc: NEWER },
  },
  {
    name: 'edge: a caption removed locally and changed on the server is removed',
    ancestor: { media: [captioned('old')] },
    local: { media: [captioned(undefined)] },
    server: { media: [captioned('theirs')] },
  },
  {
    name: 'edge: a caption removed on the server only is removed',
    ancestor: { media: [captioned('old')] },
    local: { media: [captioned('old')] },
    server: { media: [captioned(undefined)] },
  },
  {
    name: 'edge: a new media with a local caption that the server also has keeps the local caption',
    local: { media: [descriptor(ID_ONE), descriptor(ID_TWO), captioned('local', ID_THREE)] },
    server: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_THREE, { width: 300 })] },
  },
  {
    name: 'edge: the local caption is applied on top of the server descriptor and placed last',
    ancestor: { media: [captioned('old'), descriptor(ID_TWO)] },
    local: { media: [captioned('mine'), descriptor(ID_TWO)] },
    server: { media: [{ ...captioned('old'), width: 640 }, descriptor(ID_TWO)] },
  },
];

const FAVORITE_ROWS: readonly MergeRow[] = [
  { name: 'edge: favorite absent everywhere stays absent' },
  { name: 'edge: favorite set locally is kept', local: { favorite: true } },
  { name: 'edge: favorite set on the server is kept', server: { favorite: true } },
  { name: 'edge: favorite false is the same as absent and is omitted', local: { favorite: false }, server: { favorite: false } },
  { name: 'edge: favorite unchanged true stays true', ancestor: { favorite: true } },
  { name: 'edge: favorite cleared locally', ancestor: { favorite: true }, local: { favorite: false } },
  {
    name: 'edge: favorite cleared locally wins over a later server edit that kept it',
    ancestor: { favorite: true },
    local: { favorite: false, note: 'mine', editedAtUtc: OLDER },
    server: { favorite: true, note: 'theirs', editedAtUtc: NEWER },
  },
  {
    name: 'edge: favorite cleared on the server while local only edits the note',
    ancestor: { favorite: true },
    local: { note: 'mine', editedAtUtc: NEWER },
    server: { favorite: false, editedAtUtc: OLDER },
  },
];

const FIXED_ROWS: readonly MergeRow[] = [
  { name: 'createdAtUtc is always the ancestor value', local: { createdAtUtc: NEWER }, server: { createdAtUtc: OLDER } },
  { name: 'editedAtUtc is the merge time', local: { editedAtUtc: NEWER }, server: { editedAtUtc: OLDER } },
  { name: 'schemaVersion is 1' },
  { name: 'edge: nowUtc is copied verbatim', nowUtc: '2026-04-01T02:00:00+02:00' },
];

const EQUIVALENT_PATCHES: readonly (readonly [string, Patch])[] = [
  ['an identical copy', {}],
  ['a different editedAtUtc', { editedAtUtc: NEWER }],
  ['tags that differ only by case and whitespace', { tags: [' A', 'B '] }],
];

const DIFFERENT_PATCHES: readonly (readonly [string, Patch])[] = [
  ['a different note', { note: 'other' }],
  ['different tags', { tags: ['a', 'c'] }],
  ['a different takenAtUtc', { takenAtUtc: TAKEN_LOCAL }],
  ['a different utcOffsetMinutes', { utcOffsetMinutes: 60 }],
  ['a different timeZone', { timeZone: 'Europe/Paris' }],
  ['a different createdAtUtc', { createdAtUtc: NEWER }],
  ['a location where there was none', { location: PLACE }],
  ['media in another order', { media: [descriptor(ID_TWO), descriptor(ID_ONE)] }],
  ['a different media descriptor', { media: [descriptor(ID_ONE, { width: 101 }), descriptor(ID_TWO)] }],
  ['fewer media', { media: [descriptor(ID_ONE)] }],
];

function equivalentCases(): GoldenCase[] {
  const base = metadata();
  const placed = metadata({ location: PLACE });
  return [
    ...EQUIVALENT_PATCHES.map(([name, patch]) => equivalentCase(`treats ${name} as equivalent`, base, metadata(patch))),
    ...DIFFERENT_PATCHES.map(([name, patch]) => equivalentCase(`treats ${name} as different`, base, metadata(patch))),
    equivalentCase(
      'compares location numbers exactly (nudged latitude)',
      placed,
      metadata({ location: { ...PLACE, latitude: PLACE.latitude + LATITUDE_NUDGE } }),
    ),
    equivalentCase('compares location numbers exactly (copied place)', placed, metadata({ location: { ...PLACE } })),
    equivalentCase('is symmetric (left to right)', metadata({ note: 'x' }), base),
    equivalentCase('is symmetric (right to left)', base, metadata({ note: 'x' })),
    equivalentCase('treats a caption change as a difference', metadata({ media: [captioned('a')] }), metadata({ media: [captioned('b')] })),
    equivalentCase('edge: favorite false equals absent', base, metadata({ favorite: false })),
    equivalentCase('edge: favorite true differs from absent', base, metadata({ favorite: true })),
    equivalentCase('edge: the same tags in another order differ', base, metadata({ tags: ['b', 'a'] })),
    equivalentCase('edge: a tag that normalizes to nothing is ignored', base, metadata({ tags: ['a', ' ', 'b'] })),
    equivalentCase('edge: a duplicated tag differs', base, metadata({ tags: ['a', 'b', 'B'] })),
    equivalentCase('edge: NFKC applies to tags', metadata({ tags: ['\uFB01'] }), metadata({ tags: ['FI'] })),
    equivalentCase('edge: accuracy null differs from 0', metadata({ location: { ...PLACE, accuracyMeters: null } }), metadata({ location: { ...PLACE, accuracyMeters: 0 } })),
    equivalentCase('edge: a caption added differs', base, metadata({ media: [captioned('a'), descriptor(ID_TWO)] })),
    equivalentCase(
      'edge: descriptor key order does not matter',
      base,
      metadata({ media: [{ ...descriptor(ID_ONE), mediaId: ID_ONE }, descriptor(ID_TWO)] }),
    ),
    equivalentCase('edge: an empty note differs from the base note', base, metadata({ note: '' })),
    equivalentCase('edge: a different thumbnail size differs', base, metadata({ media: [descriptor(ID_ONE, { thumbnail: { width: 10, height: 8, mimeType: 'image/webp', plaintextBytes: 65 } }), descriptor(ID_TWO)] })),
  ];
}

function mergeCases(): GoldenCase[] {
  const groups: readonly (readonly [string, readonly MergeRow[]])[] = [
    ['note', NOTE_ROWS],
    ['tags', TAG_ROWS],
    ['time unit', TIME_ROWS],
    ['location', LOCATION_ROWS],
    ['media', MEDIA_ROWS],
    ['caption merge', CAPTION_ROWS],
    ['favorite', FAVORITE_ROWS],
    ['fixed fields', FIXED_ROWS],
  ];
  const base = metadata();
  return [
    ...groups.flatMap(([group, rows]) => rows.map((row) => mergeRowCase(group, row))),
    mergeCaseOf('merging three identical versions only moves editedAtUtc', base, base, base),
    mergeCaseOf(
      'merges independent fields from both sides in one pass',
      metadata(),
      metadata({ note: 'mine', editedAtUtc: NEWER }),
      metadata({ tags: ['a', 'b', 'c'], location: PLACE, editedAtUtc: OLDER }),
    ),
    mergeCaseOf(
      'caption merge: keeps a caption added locally when the server did not touch it',
      metadata({ media: [captioned(undefined)] }),
      metadata({ media: [captioned('sunset')] }),
      metadata({ media: [captioned(undefined)] }),
    ),
    mergeCaseOf(
      'caption merge: takes the server caption when only the server changed it',
      metadata({ media: [captioned('old')] }),
      metadata({ media: [captioned('old')] }),
      metadata({ media: [captioned('new')] }),
    ),
    mergeCaseOf(
      'caption merge: keeps a caption removed locally removed',
      metadata({ media: [captioned('old')] }),
      metadata({ media: [captioned(undefined)] }),
      metadata({ media: [captioned('old')] }),
    ),
    ...equivalentCases(),
  ];
}

export async function writeSyncGoldens(writer: GoldenWriter): Promise<void> {
  const files: readonly (readonly [string, () => GoldenCase[]])[] = [
    [PATHS.failures, failureCases],
    [PATHS.pushPlan, pushPlanCases],
    [PATHS.pulledEntry, pulledEntryCases],
    [PATHS.collectionsPlan, collectionsPlanCases],
    [PATHS.merge, mergeCases],
  ];
  for (const [path, build] of files) await writer.functionCases(path, build());
}

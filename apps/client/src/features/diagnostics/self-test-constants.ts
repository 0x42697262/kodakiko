import type {
  CollectionRow,
  EntryRow,
  MediaRow,
  MembershipRow,
  NewOutboxRow,
  ObjectRow,
  PinRow,
} from '../../adapters/rowstore/rowstore';

export const SELF_TEST_ACCOUNT_KEY = 'diagnostics.invalid/selftest';
export const SELF_TEST_BYTES = Uint8Array.of(1, 2, 3, 4);
export const SELF_TEST_JSON = { answer: 42 };

export const SAMPLE_COLLECTION: CollectionRow = {
  id: 'collection-1',
  kind: 'home',
  ownerId: 'owner-1',
  ownerUsername: 'owner',
  role: 'owner',
  keyMaterial: Uint8Array.of(1, 2),
  metadataBlob: Uint8Array.of(3, 4),
  metaUpdatedAt: '2026-01-01T00:00:00.000Z',
  serverLastSeq: 5,
  cursorSeq: 4,
};

export const SAMPLE_ENTRY: EntryRow = {
  id: 'entry-1',
  ownerId: 'owner-1',
  purpose: 'journal',
  revision: 2,
  metadataBlob: Uint8Array.of(5, 6),
  baseMetadataBlob: Uint8Array.of(7),
  baseRevision: 1,
  serverPendingBlob: null,
  serverPendingRevision: null,
  trashedAt: null,
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: '2026-01-02T00:00:00.000Z',
  localState: 'pending_update',
};

export const SAMPLE_OTHER_ENTRY: EntryRow = {
  ...SAMPLE_ENTRY,
  id: 'entry-2',
  localState: 'synced',
};

export const SAMPLE_MEMBERSHIP: MembershipRow = {
  collectionId: 'collection-1',
  entryId: 'entry-1',
  entryKeyWrap: Uint8Array.of(8, 9),
  seq: 3,
};

export const SAMPLE_OTHER_MEMBERSHIP: MembershipRow = {
  collectionId: 'collection-2',
  entryId: 'entry-1',
  entryKeyWrap: Uint8Array.of(10),
  seq: 1,
};

export const SAMPLE_MEDIA: MediaRow = {
  id: 'media-1',
  entryId: 'entry-1',
  mediaKeyWrap: Uint8Array.of(11, 12),
};

export const SAMPLE_OBJECT: ObjectRow = {
  id: 'object-1',
  mediaId: 'media-1',
  variant: 'original',
  sizeBytes: 100,
  plaintextSize: 60,
  localState: 'present',
  uploadState: 'uploaded',
  uploadJobId: null,
  lastAccessAt: 20,
};

export const SAMPLE_OLDER_OBJECT: ObjectRow = {
  ...SAMPLE_OBJECT,
  id: 'object-0',
  lastAccessAt: 10,
};

export const SAMPLE_THUMBNAIL_OBJECT: ObjectRow = {
  ...SAMPLE_OBJECT,
  id: 'object-2',
  variant: 'thumbnail',
  lastAccessAt: 5,
};

export const SAMPLE_PIN: PinRow = { entryId: 'entry-1', pinnedAt: 30 };

export const OUTBOX_NOW = 100;
export const OUTBOX_LIMIT = 2;

const OUTBOX_BASE: NewOutboxRow = {
  kind: 'selftest',
  payload: { id: 'entry-1' },
  state: 'queued',
  createdAt: 1,
  attempts: 0,
  nextAttemptAt: 0,
  lastErrorCode: null,
};

export const OUTBOX_ROWS: readonly NewOutboxRow[] = [
  { ...OUTBOX_BASE, nextAttemptAt: 30 },
  { ...OUTBOX_BASE, nextAttemptAt: 10 },
  { ...OUTBOX_BASE, nextAttemptAt: 20 },
  { ...OUTBOX_BASE, nextAttemptAt: 5, state: 'dead', lastErrorCode: 'INTERNAL' },
  { ...OUTBOX_BASE, nextAttemptAt: 500 },
];
export const OUTBOX_EXPECTED_DUE_ORDER: readonly number[] = [10, 20];
export const OUTBOX_EXPECTED_COUNTS = { queued: 4, dead: 1 };
export const OUTBOX_UPDATED_ATTEMPTS = 7;

export const SELF_TEST_OBJECT_ID = 'SelfTestObject0000000A';
export const SELF_TEST_FAILED_OBJECT_ID = 'SelfTestFailed0000000B';
export const SELF_TEST_CHUNKS: readonly Uint8Array[] = [
  Uint8Array.of(0, 1, 2, 3, 4, 5, 6, 7),
  Uint8Array.of(8, 9, 10, 11, 12, 13, 14, 15),
  Uint8Array.of(16, 17, 18, 19, 20, 21, 22, 23),
];
export const SELF_TEST_TOTAL_BYTES = 24;
export const SELF_TEST_READ_LENGTH = 4;
export const SELF_TEST_READ_OFFSETS: readonly number[] = [0, 10, 20];
export const UPLOAD_SELF_TEST_PLAINTEXT_BYTES = 200 * 1_048_576;
export const UPLOAD_SELF_TEST_CHUNK_BYTES = 65_536;
export const UPLOAD_SELF_TEST_NOTE = 'Upload self-test';
export const UPLOAD_SELF_TEST_MIME_TYPE = 'application/octet-stream';
export const UPLOAD_SELF_TEST_DIMENSION = 1;
export const UPLOAD_SELF_TEST_THUMBNAIL_BYTES = 1;
export const SELF_TEST_MEMBERSHIPS_PER_ENTRY = 2;
export const SELF_TEST_OBJECTS_PER_MEDIA = 3;

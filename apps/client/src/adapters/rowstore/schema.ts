import type { EntryLocalState, OutboxState } from './rowstore';

export const ROWSTORE_SCHEMA_VERSION = 1;

export const TABLES = Object.freeze({
  kv: 'kv',
  collections: 'collections',
  entries: 'entries',
  memberships: 'memberships',
  media: 'media',
  objects: 'objects',
  outbox: 'outbox',
  pins: 'pins',
});

export const INDEXES = Object.freeze({
  entriesByLocalState: 'entries_localState_idx',
  membershipsByEntry: 'memberships_entryId_idx',
  mediaByEntry: 'media_entryId_idx',
  objectsByMedia: 'objects_mediaId_idx',
  objectsByLocalStateAccess: 'objects_localState_lastAccessAt_idx',
  objectsByUploadState: 'objects_uploadState_idx',
  outboxByStateDue: 'outbox_state_nextAttemptAt_idx',
});

export const OUTBOX_STATES: readonly OutboxState[] = ['queued', 'dead'];
export const ENTRY_LOCAL_STATES: readonly EntryLocalState[] = [
  'synced',
  'pending_create',
  'pending_update',
];
export const OBJECT_PRESENT_STATE = 'present';
export const OBJECT_ORIGINAL_VARIANT = 'original';
export const OUTBOX_QUEUED_STATE: OutboxState = 'queued';
export const OUTBOX_ID_COLUMN = 'id';

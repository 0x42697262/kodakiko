import type { IDBPDatabase } from 'idb';
import { INDEXES, TABLES } from './schema';

export const SQLITE_MIGRATIONS: readonly string[] = [
  `
  CREATE TABLE ${TABLES.kv} (key TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL);
  CREATE TABLE ${TABLES.collections} (
    id TEXT PRIMARY KEY NOT NULL,
    kind TEXT NOT NULL,
    ownerId TEXT NOT NULL,
    ownerUsername TEXT NOT NULL,
    role TEXT NOT NULL,
    keyMaterial BLOB NOT NULL,
    metadataBlob BLOB NOT NULL,
    metaUpdatedAt TEXT NOT NULL,
    serverLastSeq INTEGER NOT NULL,
    cursorSeq INTEGER NOT NULL
  );
  CREATE TABLE ${TABLES.entries} (
    id TEXT PRIMARY KEY NOT NULL,
    ownerId TEXT NOT NULL,
    purpose TEXT NOT NULL,
    revision INTEGER NOT NULL,
    metadataBlob BLOB NOT NULL,
    baseMetadataBlob BLOB,
    baseRevision INTEGER,
    serverPendingBlob BLOB,
    serverPendingRevision INTEGER,
    trashedAt TEXT,
    createdAt TEXT NOT NULL,
    updatedAt TEXT NOT NULL,
    localState TEXT NOT NULL
  );
  CREATE INDEX ${INDEXES.entriesByLocalState} ON ${TABLES.entries} (localState);
  CREATE TABLE ${TABLES.memberships} (
    collectionId TEXT NOT NULL,
    entryId TEXT NOT NULL,
    entryKeyWrap BLOB NOT NULL,
    seq INTEGER NOT NULL,
    PRIMARY KEY (collectionId, entryId)
  );
  CREATE INDEX ${INDEXES.membershipsByEntry} ON ${TABLES.memberships} (entryId);
  CREATE TABLE ${TABLES.media} (
    id TEXT PRIMARY KEY NOT NULL,
    entryId TEXT NOT NULL,
    mediaKeyWrap BLOB NOT NULL
  );
  CREATE INDEX ${INDEXES.mediaByEntry} ON ${TABLES.media} (entryId);
  CREATE TABLE ${TABLES.objects} (
    id TEXT PRIMARY KEY NOT NULL,
    mediaId TEXT NOT NULL,
    variant TEXT NOT NULL,
    sizeBytes INTEGER NOT NULL,
    plaintextSize INTEGER NOT NULL,
    localState TEXT NOT NULL,
    uploadState TEXT NOT NULL,
    uploadJobId TEXT,
    lastAccessAt INTEGER NOT NULL
  );
  CREATE INDEX ${INDEXES.objectsByMedia} ON ${TABLES.objects} (mediaId);
  CREATE INDEX ${INDEXES.objectsByLocalStateAccess} ON ${TABLES.objects} (localState, lastAccessAt);
  CREATE INDEX ${INDEXES.objectsByUploadState} ON ${TABLES.objects} (uploadState);
  CREATE TABLE ${TABLES.outbox} (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL,
    payload TEXT NOT NULL,
    state TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    attempts INTEGER NOT NULL,
    nextAttemptAt INTEGER NOT NULL,
    lastErrorCode TEXT
  );
  CREATE INDEX ${INDEXES.outboxByStateDue} ON ${TABLES.outbox} (state, nextAttemptAt);
  CREATE TABLE ${TABLES.pins} (entryId TEXT PRIMARY KEY NOT NULL, pinnedAt INTEGER NOT NULL);
  `,
];

type IndexedDbMigration = (database: IDBPDatabase) => void;

export const INDEXEDDB_MIGRATIONS: readonly IndexedDbMigration[] = [
  (database) => {
    database.createObjectStore(TABLES.kv);
    database.createObjectStore(TABLES.collections, { keyPath: 'id' });
    database
      .createObjectStore(TABLES.entries, { keyPath: 'id' })
      .createIndex(INDEXES.entriesByLocalState, 'localState');
    database
      .createObjectStore(TABLES.memberships, { keyPath: ['collectionId', 'entryId'] })
      .createIndex(INDEXES.membershipsByEntry, 'entryId');
    database
      .createObjectStore(TABLES.media, { keyPath: 'id' })
      .createIndex(INDEXES.mediaByEntry, 'entryId');
    const objects = database.createObjectStore(TABLES.objects, { keyPath: 'id' });
    objects.createIndex(INDEXES.objectsByMedia, 'mediaId');
    objects.createIndex(INDEXES.objectsByLocalStateAccess, ['localState', 'lastAccessAt']);
    objects.createIndex(INDEXES.objectsByUploadState, 'uploadState');
    database
      .createObjectStore(TABLES.outbox, { keyPath: 'id', autoIncrement: true })
      .createIndex(INDEXES.outboxByStateDue, ['state', 'nextAttemptAt']);
    database.createObjectStore(TABLES.pins, { keyPath: 'entryId' });
  },
];

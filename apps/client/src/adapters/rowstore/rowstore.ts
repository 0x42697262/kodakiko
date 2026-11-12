import type { Sodium } from '@kodakiko/core';
import type { z } from 'zod';
import type { StorageKey } from '../../keys/storage-keys';

export type CollectionKind = 'home' | 'shared' | 'link';
export type CollectionRole = 'owner' | 'viewer';
export type EntryPurpose = 'journal' | 'link_copy';
export type EntryLocalState = 'synced' | 'pending_create' | 'pending_update';
export type ObjectVariant = 'original' | 'thumbnail';
export type ObjectLocalState = 'missing' | 'present' | 'unavailable';
export type ObjectUploadState = 'local' | 'queued' | 'uploading' | 'uploaded' | 'dead';
export type OutboxState = 'queued' | 'dead';

export type CollectionRow = {
  readonly id: string;
  readonly kind: CollectionKind;
  readonly ownerId: string;
  readonly ownerUsername: string;
  readonly role: CollectionRole;
  readonly keyMaterial: Uint8Array;
  readonly metadataBlob: Uint8Array;
  readonly metaUpdatedAt: string;
  readonly serverLastSeq: number;
  readonly cursorSeq: number;
};

export type EntryRow = {
  readonly id: string;
  readonly ownerId: string;
  readonly purpose: EntryPurpose;
  readonly revision: number;
  readonly metadataBlob: Uint8Array;
  readonly baseMetadataBlob: Uint8Array | null;
  readonly baseRevision: number | null;
  readonly serverPendingBlob: Uint8Array | null;
  readonly serverPendingRevision: number | null;
  readonly trashedAt: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly localState: EntryLocalState;
};

export type MembershipRow = {
  readonly collectionId: string;
  readonly entryId: string;
  readonly entryKeyWrap: Uint8Array;
  readonly seq: number;
};

export type MediaRow = {
  readonly id: string;
  readonly entryId: string;
  readonly mediaKeyWrap: Uint8Array;
};

export type ObjectRow = {
  readonly id: string;
  readonly mediaId: string;
  readonly variant: ObjectVariant;
  readonly sizeBytes: number;
  readonly plaintextSize: number;
  readonly localState: ObjectLocalState;
  readonly uploadState: ObjectUploadState;
  readonly uploadJobId: string | null;
  readonly lastAccessAt: number;
};

export type OutboxRow = {
  readonly id: number;
  readonly kind: string;
  readonly payload: unknown;
  readonly state: OutboxState;
  readonly createdAt: number;
  readonly attempts: number;
  readonly nextAttemptAt: number;
  readonly lastErrorCode: string | null;
};

export type NewOutboxRow = Omit<OutboxRow, 'id'>;

export type PinRow = { readonly entryId: string; readonly pinnedAt: number };

export interface TableOps<Row, Key> {
  readonly get: (key: Key) => Promise<Row | null>;
  readonly put: (row: Row) => Promise<void>;
  readonly delete: (key: Key) => Promise<void>;
  readonly all: () => Promise<readonly Row[]>;
}

export interface RowStoreTx {
  readonly collections: TableOps<CollectionRow, string>;
  readonly entries: TableOps<EntryRow, string> & {
    readonly byLocalState: (states: readonly EntryLocalState[]) => Promise<readonly EntryRow[]>;
  };
  readonly memberships: TableOps<MembershipRow, readonly [string, string]> & {
    readonly byEntry: (entryId: string) => Promise<readonly MembershipRow[]>;
    readonly byCollection: (collectionId: string) => Promise<readonly MembershipRow[]>;
  };
  readonly media: TableOps<MediaRow, string> & {
    readonly byEntry: (entryId: string) => Promise<readonly MediaRow[]>;
  };
  readonly objects: TableOps<ObjectRow, string> & {
    readonly byMedia: (mediaId: string) => Promise<readonly ObjectRow[]>;
    readonly presentOriginalsByAccess: () => Promise<readonly ObjectRow[]>;
  };
  readonly outbox: {
    readonly enqueue: (row: NewOutboxRow) => Promise<number>;
    readonly due: (now: number, limit: number) => Promise<readonly OutboxRow[]>;
    readonly update: (row: OutboxRow) => Promise<void>;
    readonly delete: (id: number) => Promise<void>;
    readonly countByState: () => Promise<Readonly<Record<OutboxState, number>>>;
    readonly all: () => Promise<readonly OutboxRow[]>;
  };
  readonly pins: TableOps<PinRow, string>;
}

export interface RowStore {
  readonly open: (accountKey: string) => Promise<void>;
  readonly close: () => Promise<void>;
  readonly destroy: (accountKey: string) => Promise<void>;
  readonly transaction: <T>(work: (tx: RowStoreTx) => Promise<T>) => Promise<T>;
  readonly kvGet: (key: StorageKey) => Promise<Uint8Array | null>;
  readonly kvGetJson: <T>(key: StorageKey, schema: z.ZodType<T>) => Promise<T | null>;
  readonly kvSet: (key: StorageKey, value: Uint8Array) => Promise<void>;
  readonly kvSetJson: (key: StorageKey, value: unknown) => Promise<void>;
  readonly kvDelete: (key: StorageKey) => Promise<void>;
}

export declare function createRowStore(deps: { readonly sodium: Sodium }): RowStore;

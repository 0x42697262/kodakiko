import type { Generated } from 'kysely';
import type {
  CollectionKind,
  EntryPurpose,
  ObjectState,
  ObjectVariant,
  UserRole,
  UserStatus,
} from '../../domain/enums.ts';

export interface UsersTable {
  id: string;
  username: string;
  role: UserRole;
  status: UserStatus;
  loginKeyHash: Buffer;
  recoveryLoginKeyHash: Buffer;
  kdfSalt: Buffer;
  kdfOpsLimit: number;
  kdfMemLimitBytes: number;
  masterKeyByKek: Buffer;
  masterKeyByRecovery: Buffer;
  recoveryKeyByMaster: Buffer;
  publicKey: Buffer;
  privateKeyByMaster: Buffer;
  quotaBytes: number | null;
  usedBytes: Generated<number>;
  deletionDueAt: Date | null;
  createdAt: Date;
  updatedAt: Date;
}

export interface SessionsTable {
  id: string;
  userId: string;
  tokenHash: Buffer;
  deviceLabel: string;
  createdAt: Date;
  lastUsedAt: Date;
  expiresAt: Date;
}

export interface InvitesTable {
  id: string;
  tokenHash: Buffer;
  createdBy: string | null;
  role: UserRole;
  expiresAt: Date;
  redeemedBy: string | null;
  redeemedAt: Date | null;
  revokedAt: Date | null;
  createdAt: Date;
}

export interface CollectionsTable {
  id: string;
  ownerId: string;
  kind: CollectionKind;
  keyByOwner: Buffer;
  metadataBlob: Buffer;
  lastSeq: Generated<number>;
  tombstonesPrunedThroughSeq: Generated<number>;
  createdAt: Date;
  updatedAt: Date;
  metaUpdatedAt: Date;
}

export interface CollectionSharesTable {
  collectionId: string;
  recipientId: string;
  sealedCollectionKey: Buffer;
  createdAt: Date;
}

export interface EntriesTable {
  id: string;
  ownerId: string;
  purpose: EntryPurpose;
  revision: number;
  metadataBlob: Buffer;
  trashedAt: Date | null;
  createdAt: Date;
  updatedAt: Date;
}

export interface EntryMembershipsTable {
  collectionId: string;
  entryId: string;
  entryKeyByCollection: Buffer;
  seq: number;
  addedAt: Date;
}

export interface MembershipTombstonesTable {
  collectionId: string;
  entryId: string;
  seq: number;
  removedAt: Date;
}

export interface MediaTable {
  id: string;
  entryId: string;
  mediaKeyByEntry: Buffer;
  createdAt: Date;
}

export interface ObjectsTable {
  id: string;
  ownerId: string;
  mediaId: string | null;
  variant: ObjectVariant;
  objectKey: string;
  sizeBytes: number;
  state: ObjectState;
  multipartUploadId: string | null;
  createdAt: Date;
  uploadedAt: Date | null;
  deleteAfter: Date | null;
}

export interface PublicLinksTable {
  id: string;
  collectionId: string;
  accessTokenHash: Buffer;
  expiresAt: Date | null;
  maxViews: number | null;
  viewCount: Generated<number>;
  createdAt: Date;
}

export interface Database {
  users: UsersTable;
  sessions: SessionsTable;
  invites: InvitesTable;
  collections: CollectionsTable;
  collectionShares: CollectionSharesTable;
  entries: EntriesTable;
  entryMemberships: EntryMembershipsTable;
  membershipTombstones: MembershipTombstonesTable;
  media: MediaTable;
  objects: ObjectsTable;
  publicLinks: PublicLinksTable;
}

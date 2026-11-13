import { z } from 'zod';
import { bytesSchema, idSchema } from './ids.ts';

export const COLLECTION_KINDS = ['home', 'shared', 'link'] as const;
export const COLLECTION_ROLES = ['owner', 'viewer'] as const;

const isoTime = z.iso.datetime();

export const collectionDto = z.object({
  id: idSchema,
  kind: z.enum(COLLECTION_KINDS),
  role: z.enum(COLLECTION_ROLES),
  ownerId: idSchema,
  ownerUsername: z.string(),
  keyMaterial: bytesSchema,
  metadataBlob: bytesSchema,
  lastSeq: z.number().int().nonnegative(),
  createdAt: isoTime,
  metaUpdatedAt: isoTime,
  recipientCount: z.number().int().nonnegative().nullable().default(null),
});

export const collectionListResponse = z.object({
  items: z.array(collectionDto),
  nextCursor: z.string().nullable(),
});

export const collectionCreateRequest = z.object({
  id: idSchema,
  kind: z.enum(['shared', 'link']),
  keyByOwner: bytesSchema,
  metadataBlob: bytesSchema,
});

export const collectionEntryAddRequest = z.object({ entryId: idSchema, entryKeyByCollection: bytesSchema });

export const collectionEntryParams = z.object({ id: idSchema, entryId: idSchema });

export const shareCreateRequest = z.object({ recipientUserId: idSchema, sealedCollectionKey: bytesSchema });

export const shareParams = z.object({ id: idSchema, recipientUserId: idSchema });

export const shareDto = z.object({
  recipientUserId: idSchema,
  recipientUsername: z.string(),
  createdAt: isoTime,
});

export const shareListResponse = z.object({ items: z.array(shareDto) });

export const collectionPatchRequest = z.object({ metadataBlob: bytesSchema });

export const collectionIdParams = z.object({ id: idSchema });

export const collectionListQuery = z.object({
  cursor: z.string().min(1).optional(),
  limit: z.coerce.number().int().min(1).optional(),
});

export type CollectionDto = z.infer<typeof collectionDto>;
export type CollectionListResponse = z.infer<typeof collectionListResponse>;
export type CollectionPatchRequest = z.infer<typeof collectionPatchRequest>;
export type CollectionListQuery = z.infer<typeof collectionListQuery>;
export type CollectionCreateRequest = z.infer<typeof collectionCreateRequest>;
export type CollectionEntryAddRequest = z.infer<typeof collectionEntryAddRequest>;
export type ShareCreateRequest = z.infer<typeof shareCreateRequest>;
export type ShareDto = z.infer<typeof shareDto>;
export type ShareListResponse = z.infer<typeof shareListResponse>;

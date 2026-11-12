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
});

export const collectionListResponse = z.object({
  items: z.array(collectionDto),
  nextCursor: z.string().nullable(),
});

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

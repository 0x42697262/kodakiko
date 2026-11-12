import { z } from 'zod';
import { COLLECTION_KINDS, COLLECTION_ROLES } from './collections.ts';
import { entryDto } from './entries.ts';
import { bytesSchema, idSchema } from './ids.ts';
import { mediaDto } from './media.ts';
import { objectDto } from './objects.ts';

export const diffQuery = z.object({
  since: z.coerce.number().int().min(0),
  limit: z.coerce.number().int().min(1).optional(),
});

export const diffPage = z.object({
  collectionId: idSchema,
  memberships: z.array(
    z.object({
      entryId: idSchema,
      entryKeyByCollection: bytesSchema.nullable(),
      seq: z.number().int().nonnegative(),
      removed: z.boolean(),
    }),
  ),
  entries: z.array(entryDto),
  media: z.array(mediaDto),
  objects: z.array(objectDto),
  nextCursor: z.number().int().nonnegative(),
  hasMore: z.boolean(),
  resyncRequired: z.boolean(),
});

export const syncStateResponse = z.object({
  collections: z.array(
    z.object({
      id: idSchema,
      kind: z.enum(COLLECTION_KINDS),
      role: z.enum(COLLECTION_ROLES),
      lastSeq: z.number().int().nonnegative(),
      tombstonesPrunedThroughSeq: z.number().int().nonnegative(),
      metaUpdatedAt: z.iso.datetime(),
    }),
  ),
});

export type DiffQuery = z.infer<typeof diffQuery>;
export type DiffPage = z.infer<typeof diffPage>;
export type SyncStateResponse = z.infer<typeof syncStateResponse>;

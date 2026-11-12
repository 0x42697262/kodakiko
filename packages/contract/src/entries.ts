import { z } from 'zod';
import { bytesSchema, idSchema } from './ids.ts';
import { MEDIA_PER_ENTRY_HARD_MAX, MEMBERSHIPS_PER_ENTRY_MAX } from './limits.ts';

export const ENTRY_PURPOSES = ['journal', 'link_copy'] as const;

const isoTime = z.iso.datetime();

export const entryDto = z.object({
  id: idSchema,
  ownerId: idSchema,
  purpose: z.enum(ENTRY_PURPOSES),
  revision: z.number().int().min(1),
  metadataBlob: bytesSchema,
  trashedAt: isoTime.nullable(),
  createdAt: isoTime,
  updatedAt: isoTime,
});

export const entryCreateRequest = z.object({
  id: idSchema,
  purpose: z.enum(ENTRY_PURPOSES),
  metadataBlob: bytesSchema,
  memberships: z
    .array(z.object({ collectionId: idSchema, entryKeyByCollection: bytesSchema }))
    .min(1)
    .max(MEMBERSHIPS_PER_ENTRY_MAX),
  media: z
    .array(z.object({ mediaId: idSchema, mediaKeyByEntry: bytesSchema }))
    .min(1)
    .max(MEDIA_PER_ENTRY_HARD_MAX),
});

export const entryPatchRequest = z.object({
  metadataBlob: bytesSchema,
  baseRevision: z.number().int().min(1),
});

export const revisionConflictDetails = z.object({
  revision: z.number().int().min(1),
  metadataBlob: bytesSchema,
});

export const entryIdParams = z.object({ id: idSchema });

export type EntryDto = z.infer<typeof entryDto>;
export type EntryCreateRequest = z.infer<typeof entryCreateRequest>;
export type EntryPatchRequest = z.infer<typeof entryPatchRequest>;
export type RevisionConflictDetails = z.infer<typeof revisionConflictDetails>;

import { z } from 'zod';
import { idSchema } from './ids.ts';

export const OUTBOX_KINDS = [
  'createEntry',
  'updateEntryMeta',
  'addMedia',
  'removeMedia',
  'trashEntry',
  'restoreEntry',
  'deleteEntry',
] as const;

export type OutboxKind = (typeof OUTBOX_KINDS)[number];

export const entryOutboxPayloadSchemas = {
  createEntry: z.object({ entryId: idSchema }),
  updateEntryMeta: z.object({ entryId: idSchema }),
  addMedia: z.object({ entryId: idSchema, mediaId: idSchema }),
  removeMedia: z.object({ entryId: idSchema, mediaId: idSchema }),
  trashEntry: z.object({ entryId: idSchema }),
  restoreEntry: z.object({ entryId: idSchema }),
  deleteEntry: z.object({ entryId: idSchema }),
} as const;

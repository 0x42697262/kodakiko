import { z } from 'zod';
import { bytesSchema, idSchema } from './ids.ts';

export const mediaDto = z.object({
  id: idSchema,
  entryId: idSchema,
  mediaKeyByEntry: bytesSchema,
  createdAt: z.iso.datetime(),
});

export const mediaCreateRequest = z.object({
  mediaId: idSchema,
  mediaKeyByEntry: bytesSchema,
});

export const mediaIdParams = z.object({ id: idSchema });

export type MediaDto = z.infer<typeof mediaDto>;
export type MediaCreateRequest = z.infer<typeof mediaCreateRequest>;

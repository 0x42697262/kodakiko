import { z } from 'zod';
import { idSchema } from './ids.ts';
import { MIN_OBJECT_BYTES } from './limits.ts';

export const OBJECT_VARIANTS = ['original', 'thumbnail'] as const;
export const OBJECT_STATES = ['pending', 'uploaded', 'deleting'] as const;

export const objectVariantSchema = z.enum(OBJECT_VARIANTS);
export const objectStateSchema = z.enum(OBJECT_STATES);

export const presignRequest = z.object({
  id: idSchema,
  mediaId: idSchema,
  variant: objectVariantSchema,
  sizeBytes: z.number().int().min(MIN_OBJECT_BYTES),
});

const partNumberSchema = z.number().int().min(1);
const partUrlSchema = z.object({ partNumber: partNumberSchema, url: z.url() });
const expiresAtSchema = z.iso.datetime();

export const presignResponse = z.discriminatedUnion('mode', [
  z.object({ mode: z.literal('single'), url: z.url(), expiresAt: expiresAtSchema }),
  z.object({
    mode: z.literal('multipart'),
    partSizeBytes: z.number().int().min(1),
    partUrls: z.array(partUrlSchema),
    expiresAt: expiresAtSchema,
  }),
]);

export const partsResponse = z.object({
  partSizeBytes: z.number().int().min(1),
  uploadedParts: z.array(
    z.object({ partNumber: partNumberSchema, sizeBytes: z.number().int().min(0) }),
  ),
  partUrls: z.array(partUrlSchema),
  expiresAt: expiresAtSchema,
});

export const objectDto = z.object({
  id: idSchema,
  mediaId: idSchema,
  variant: objectVariantSchema,
  sizeBytes: z.number().int().min(MIN_OBJECT_BYTES),
  state: objectStateSchema,
});

export const downloadResponse = z.object({ url: z.url(), expiresAt: expiresAtSchema });

export const objectIdParams = z.object({ id: idSchema });

export type PresignRequest = z.infer<typeof presignRequest>;
export type PresignResponse = z.infer<typeof presignResponse>;
export type PartsResponse = z.infer<typeof partsResponse>;
export type ObjectDto = z.infer<typeof objectDto>;
export type DownloadResponse = z.infer<typeof downloadResponse>;

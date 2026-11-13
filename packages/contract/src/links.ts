import { z } from 'zod';
import { bytesSchema, idSchema } from './ids.ts';
import { entryDto } from './entries.ts';
import { mediaDto } from './media.ts';
import { objectDto } from './objects.ts';

export const LINK_EXPIRIES = ['1h', '1d', '7d', '14d', '30d', 'never', 'one_view'] as const;
export const LINK_TOKEN_HEADER = 'x-kodakiko-link-token';
export const LINK_VIEW_TOKEN_HEADER = 'x-kodakiko-view-token';

const isoTime = z.iso.datetime();

export const linkExpirySchema = z.enum(LINK_EXPIRIES);

export const publicLinkCreateRequest = z.object({
  collectionId: idSchema,
  accessToken: bytesSchema,
  expiry: linkExpirySchema,
});

export const publicLinkDto = z.object({
  linkId: idSchema,
  collectionId: idSchema,
  expiresAt: isoTime.nullable(),
  maxViews: z.number().int().positive().nullable(),
  viewCount: z.number().int().nonnegative(),
  createdAt: isoTime,
});

export const publicLinkListResponse = z.object({ items: z.array(publicLinkDto) });

export const publicLinkParams = z.object({ linkId: idSchema });

export const publicLinkObjectParams = z.object({ linkId: idSchema, objectId: idSchema });

export const linkOpenResponse = z.object({
  collectionId: idSchema,
  metadataBlob: bytesSchema,
  expiresAt: isoTime.nullable(),
  maxViews: z.number().int().positive().nullable(),
  viewToken: z.string().min(1),
});

export const linkDownload = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('presigned'), url: z.url(), expiresAt: isoTime }),
  z.object({ kind: z.literal('proxy'), path: z.string().startsWith('/') }),
]);

export const linkObjectDto = objectDto.extend({ download: linkDownload });

export const linkEntryItem = z.object({
  entry: entryDto,
  entryKeyByCollection: bytesSchema,
  media: z.array(mediaDto),
  objects: z.array(linkObjectDto),
});

export const linkEntriesQuery = z.object({
  cursor: z.string().min(1).optional(),
  limit: z.coerce.number().int().min(1).optional(),
});

export const linkEntriesResponse = z.object({
  items: z.array(linkEntryItem),
  nextCursor: z.string().nullable(),
});

export type LinkExpiry = z.infer<typeof linkExpirySchema>;
export type PublicLinkCreateRequest = z.infer<typeof publicLinkCreateRequest>;
export type PublicLinkDto = z.infer<typeof publicLinkDto>;
export type PublicLinkListResponse = z.infer<typeof publicLinkListResponse>;
export type LinkOpenResponse = z.infer<typeof linkOpenResponse>;
export type LinkDownload = z.infer<typeof linkDownload>;
export type LinkEntryItem = z.infer<typeof linkEntryItem>;
export type LinkEntriesResponse = z.infer<typeof linkEntriesResponse>;

import { z } from 'zod';
import { idSchema } from './ids.ts';
import {
  COLLECTION_NAME_MAX_CHARS,
  FILE_NAME_MAX_CHARS,
  LATITUDE_MAX,
  LATITUDE_MIN,
  LONGITUDE_MAX,
  LONGITUDE_MIN,
  MEDIA_PER_ENTRY_HARD_MAX,
  MIME_TYPE_MAX_CHARS,
  NOTE_MAX_CHARS,
  TAGS_PER_ENTRY_MAX,
  TAG_MAX_CHARS,
  TIME_ZONE_MAX_CHARS,
  UTC_OFFSET_MINUTES_MAX,
  UTC_OFFSET_MINUTES_MIN,
} from './limits.ts';

export const TagName = z.string().min(1).max(TAG_MAX_CHARS);

export const MediaDescriptorV1 = z.object({
  mediaId: idSchema,
  kind: z.enum(['photo', 'video']),
  mimeType: z.string().max(MIME_TYPE_MAX_CHARS),
  width: z.number().int().positive(),
  height: z.number().int().positive(),
  durationMs: z.number().int().nonnegative().nullable(),
  originalPlaintextBytes: z.number().int().nonnegative(),
  thumbnail: z.object({
    width: z.number().int().positive(),
    height: z.number().int().positive(),
    mimeType: z.literal('image/webp'),
    plaintextBytes: z.number().int().positive(),
  }),
  capturedAtUtc: z.iso.datetime().nullable(),
  originalFileName: z.string().max(FILE_NAME_MAX_CHARS).nullable(),
});

export const EntryMetadataV1 = z.object({
  schemaVersion: z.literal(1),
  takenAtUtc: z.iso.datetime(),
  utcOffsetMinutes: z.number().int().min(UTC_OFFSET_MINUTES_MIN).max(UTC_OFFSET_MINUTES_MAX),
  timeZone: z.string().max(TIME_ZONE_MAX_CHARS).nullable(),
  note: z.string().max(NOTE_MAX_CHARS),
  tags: z.array(TagName).max(TAGS_PER_ENTRY_MAX),
  location: z
    .object({
      latitude: z.number().min(LATITUDE_MIN).max(LATITUDE_MAX),
      longitude: z.number().min(LONGITUDE_MIN).max(LONGITUDE_MAX),
      accuracyMeters: z.number().nonnegative().nullable(),
      source: z.enum(['exif', 'device']),
    })
    .nullable(),
  media: z.array(MediaDescriptorV1).min(1).max(MEDIA_PER_ENTRY_HARD_MAX),
  createdAtUtc: z.iso.datetime(),
  editedAtUtc: z.iso.datetime(),
});

export const CollectionMetadataV1 = z.object({
  schemaVersion: z.literal(1),
  name: z.string().min(1).max(COLLECTION_NAME_MAX_CHARS),
  createdAtUtc: z.iso.datetime(),
});

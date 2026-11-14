import type { z } from 'zod';
import { EntryMetadataV1 } from '@kodakiko/contract';
import type { MediaDescriptorV1 } from '@kodakiko/contract';
import { earliestTime, type ResolvedTime } from '../media/capture-time.ts';
import type { GeoPoint } from '../media/exif.ts';
import { InvalidEditError } from './errors.ts';
import { normalizeTag, sortTags } from './tags.ts';
import { DOMAIN_TEXT } from './text.ts';

type Metadata = z.infer<typeof EntryMetadataV1>;
type Descriptor = z.infer<typeof MediaDescriptorV1>;
type Place = Metadata['location'];

export type EntryDraft = {
  readonly media: readonly Descriptor[];
  readonly times: readonly ResolvedTime[];
  readonly note: string;
  readonly tags: readonly string[];
  readonly mediaLocations: readonly (GeoPoint | null)[];
  readonly deviceLocation: { readonly point: GeoPoint; readonly accuracyMeters: number | null } | null;
  readonly timeZone: string | null;
  readonly nowUtc: string;
};

export type EntryEdit = {
  readonly note?: string;
  readonly tags?: readonly string[];
  readonly time?: { readonly takenAtUtc: string; readonly utcOffsetMinutes: number };
  readonly location?: Place;
  readonly mediaOrder?: readonly string[];
  readonly addMedia?: readonly Descriptor[];
  readonly removeMediaId?: string;
  readonly favorite?: boolean;
};

function cleanTags(tags: readonly string[]): readonly string[] {
  const cleaned = new Set<string>();
  for (const tag of tags) {
    const normalized = normalizeTag(tag);
    if (normalized === null) throw new InvalidEditError(DOMAIN_TEXT.invalidTag);
    cleaned.add(normalized);
  }
  return sortTags([...cleaned]);
}

function earliestLocatedPoint(draft: EntryDraft): GeoPoint | null {
  let bestPoint: GeoPoint | null = null;
  let bestAt = Number.POSITIVE_INFINITY;
  for (const [position, point] of draft.mediaLocations.entries()) {
    const time = draft.times[position];
    if (point === null || time === undefined) continue;
    const at = Date.parse(time.capturedAtUtc);
    if (at < bestAt) {
      bestPoint = point;
      bestAt = at;
    }
  }
  return bestPoint;
}

function draftLocation(draft: EntryDraft): Place {
  const point = earliestLocatedPoint(draft);
  if (point !== null) {
    return {
      latitude: point.latitude,
      longitude: point.longitude,
      accuracyMeters: null,
      source: 'exif',
    };
  }
  if (draft.deviceLocation === null) return null;
  return {
    latitude: draft.deviceLocation.point.latitude,
    longitude: draft.deviceLocation.point.longitude,
    accuracyMeters: draft.deviceLocation.accuracyMeters,
    source: 'device',
  };
}

export function buildNewEntryMetadata(draft: EntryDraft): Metadata {
  const taken = earliestTime(draft.times);
  return EntryMetadataV1.parse({
    schemaVersion: 1,
    takenAtUtc: taken.capturedAtUtc,
    utcOffsetMinutes: taken.utcOffsetMinutes,
    timeZone: draft.timeZone,
    note: draft.note,
    tags: cleanTags(draft.tags),
    location: draftLocation(draft),
    media: draft.media,
    createdAtUtc: draft.nowUtc,
    editedAtUtc: draft.nowUtc,
  });
}

function reordered(media: readonly Descriptor[], order: readonly string[]): Descriptor[] {
  const byId = new Map<string, Descriptor>(media.map((item) => [item.mediaId, item]));
  const isPermutation =
    order.length === media.length &&
    new Set(order).size === order.length &&
    order.every((mediaId) => byId.has(mediaId));
  if (!isPermutation) throw new InvalidEditError(DOMAIN_TEXT.mediaOrderNotPermutation);
  return order.map((mediaId) => byId.get(mediaId) as Descriptor);
}

function withoutMedia(media: readonly Descriptor[], mediaId: string): Descriptor[] {
  if (!media.some((item) => item.mediaId === mediaId)) {
    throw new InvalidEditError(DOMAIN_TEXT.unknownMedia);
  }
  return media.filter((item) => item.mediaId !== mediaId);
}

export function applyEntryEdit(current: Metadata, edit: EntryEdit, nowUtc: string): Metadata {
  let media: Descriptor[] = [...current.media];
  if (edit.mediaOrder !== undefined) media = reordered(media, edit.mediaOrder);
  if (edit.removeMediaId !== undefined) media = withoutMedia(media, edit.removeMediaId);
  if (edit.addMedia !== undefined) media = [...media, ...edit.addMedia];
  if (media.length === 0) throw new InvalidEditError(DOMAIN_TEXT.removeLastMedia);
  const { favorite: wasFavorite, ...rest } = current;
  const favorite = edit.favorite ?? wasFavorite === true;
  return EntryMetadataV1.parse({
    ...rest,
    ...(favorite ? { favorite: true } : {}),
    note: edit.note ?? current.note,
    tags: edit.tags === undefined ? current.tags : cleanTags(edit.tags),
    takenAtUtc: edit.time?.takenAtUtc ?? current.takenAtUtc,
    utcOffsetMinutes: edit.time?.utcOffsetMinutes ?? current.utcOffsetMinutes,
    location: edit.location === undefined ? current.location : edit.location,
    media,
    editedAtUtc: nowUtc,
  });
}

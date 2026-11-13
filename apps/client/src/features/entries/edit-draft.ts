import type { EntryMetadataV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import type { MetadataEdit } from '../../services/entries/entries-service';

type Metadata = z.infer<typeof EntryMetadataV1>;

export type EditDraft = {
  readonly mediaIds: readonly string[];
  readonly note: string;
  readonly tags: readonly string[];
  readonly time: { readonly takenAtUtc: string; readonly utcOffsetMinutes: number };
  readonly location: Metadata['location'];
};

export type DraftDiff = { readonly removedMediaIds: readonly string[]; readonly edit: MetadataEdit };

export function draftFromMetadata(current: Metadata): EditDraft {
  return {
    mediaIds: current.media.map((item) => item.mediaId),
    note: current.note,
    tags: current.tags,
    time: { takenAtUtc: current.takenAtUtc, utcOffsetMinutes: current.utcOffsetMinutes },
    location: current.location,
  };
}

function sameList(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value, position) => value === right[position]);
}

function sameTags(left: readonly string[], right: readonly string[]): boolean {
  return sameList([...left].sort(), [...right].sort());
}

function sameLocation(left: Metadata['location'], right: Metadata['location']): boolean {
  if (left === null || right === null) return left === right;
  return (
    left.latitude === right.latitude &&
    left.longitude === right.longitude &&
    left.accuracyMeters === right.accuracyMeters &&
    left.source === right.source
  );
}

export function diffDraft(current: Metadata, draft: EditDraft): DraftDiff {
  const kept = new Set(draft.mediaIds);
  const currentIds = current.media.map((item) => item.mediaId);
  const removedMediaIds = currentIds.filter((mediaId) => !kept.has(mediaId));
  const remaining = currentIds.filter((mediaId) => kept.has(mediaId));
  const edit: {
    note?: string;
    tags?: readonly string[];
    time?: EditDraft['time'];
    location?: Metadata['location'];
    mediaOrder?: readonly string[];
  } = {};
  if (draft.note !== current.note) edit.note = draft.note;
  if (!sameTags(draft.tags, current.tags)) edit.tags = draft.tags;
  if (
    draft.time.takenAtUtc !== current.takenAtUtc ||
    draft.time.utcOffsetMinutes !== current.utcOffsetMinutes
  ) {
    edit.time = draft.time;
  }
  if (!sameLocation(draft.location, current.location)) edit.location = draft.location;
  if (!sameList(draft.mediaIds, remaining)) edit.mediaOrder = draft.mediaIds;
  return { removedMediaIds, edit };
}

export function draftChanged(diff: DraftDiff): boolean {
  return diff.removedMediaIds.length > 0 || Object.keys(diff.edit).length > 0;
}

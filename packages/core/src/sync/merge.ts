import type { z } from 'zod';
import type { EntryMetadataV1, MediaDescriptorV1 } from '@kodakiko/contract';
import { normalizeTag } from '../domain/tags.ts';

type Metadata = z.infer<typeof EntryMetadataV1>;
type Descriptor = z.infer<typeof MediaDescriptorV1>;
type Place = Metadata['location'];

const TIME_UNIT_KEYS = ['takenAtUtc', 'utcOffsetMinutes', 'timeZone'] as const;

function sameValue(left: unknown, right: unknown): boolean {
  if (left === right) return true;
  if (typeof left !== 'object' || typeof right !== 'object' || left === null || right === null) {
    return false;
  }
  if (Array.isArray(left) || Array.isArray(right)) {
    return (
      Array.isArray(left) &&
      Array.isArray(right) &&
      left.length === right.length &&
      left.every((item, position) => sameValue(item, right[position]))
    );
  }
  const leftRecord = left as Record<string, unknown>;
  const rightRecord = right as Record<string, unknown>;
  const keys = new Set([...Object.keys(leftRecord), ...Object.keys(rightRecord)]);
  return [...keys].every((key) => sameValue(leftRecord[key], rightRecord[key]));
}

function normalizedTags(tags: readonly string[]): string[] {
  return tags.flatMap((tag) => {
    const normalized = normalizeTag(tag);
    return normalized === null ? [] : [normalized];
  });
}

function laterEditWins(local: Metadata, server: Metadata): 'local' | 'server' {
  return Date.parse(server.editedAtUtc) > Date.parse(local.editedAtUtc) ? 'server' : 'local';
}

function pickSide<Value>(
  changedLocally: boolean,
  changedOnServer: boolean,
  local: Metadata,
  server: Metadata,
  localValue: Value,
  serverValue: Value,
): Value {
  if (changedLocally && !changedOnServer) return localValue;
  if (!changedLocally && changedOnServer) return serverValue;
  if (!changedLocally) return localValue;
  return laterEditWins(local, server) === 'server' ? serverValue : localValue;
}

function mergeTags(ancestor: Metadata, local: Metadata, server: Metadata): string[] {
  const base = new Set(normalizedTags(ancestor.tags));
  const localTags = new Set(normalizedTags(local.tags));
  const serverTags = new Set(normalizedTags(server.tags));
  const merged = new Set<string>();
  for (const tag of base) {
    if (localTags.has(tag) && serverTags.has(tag)) merged.add(tag);
  }
  for (const tag of [...localTags, ...serverTags]) {
    if (!base.has(tag)) merged.add(tag);
  }
  return [...merged].sort();
}

function mergeMedia(ancestor: Metadata, local: Metadata, server: Metadata): Descriptor[] {
  const ancestorIds = new Set(ancestor.media.map((item) => item.mediaId));
  const localById = new Map(local.media.map((item) => [item.mediaId, item]));
  const serverById = new Map(server.media.map((item) => [item.mediaId, item]));
  const merged: Descriptor[] = [];
  for (const item of local.media) {
    const removedOnServer = ancestorIds.has(item.mediaId) && !serverById.has(item.mediaId);
    if (!removedOnServer) merged.push(serverById.get(item.mediaId) ?? item);
  }
  for (const item of server.media) {
    const knownLocally = localById.has(item.mediaId);
    const removedLocally = ancestorIds.has(item.mediaId) && !knownLocally;
    if (!knownLocally && !removedLocally) merged.push(item);
  }
  return merged;
}

function changedFrom(ancestor: Metadata, other: Metadata, keys: readonly (keyof Metadata)[]) {
  return keys.some((key) => !sameValue(ancestor[key], other[key]));
}

export function mergeMetadata(
  ancestor: Metadata,
  local: Metadata,
  server: Metadata,
  nowUtc: string,
): Metadata {
  const timeSide = pickSide(
    changedFrom(ancestor, local, TIME_UNIT_KEYS),
    changedFrom(ancestor, server, TIME_UNIT_KEYS),
    local,
    server,
    local,
    server,
  );
  const location = pickSide<Place>(
    !sameValue(ancestor.location, local.location),
    !sameValue(ancestor.location, server.location),
    local,
    server,
    local.location,
    server.location,
  );
  return {
    schemaVersion: 1,
    takenAtUtc: timeSide.takenAtUtc,
    utcOffsetMinutes: timeSide.utcOffsetMinutes,
    timeZone: timeSide.timeZone,
    note: pickSide(
      ancestor.note !== local.note,
      ancestor.note !== server.note,
      local,
      server,
      local.note,
      server.note,
    ),
    tags: mergeTags(ancestor, local, server),
    location,
    media: mergeMedia(ancestor, local, server),
    createdAtUtc: ancestor.createdAtUtc,
    editedAtUtc: nowUtc,
    ...(pickSide(
      (ancestor.favorite === true) !== (local.favorite === true),
      (ancestor.favorite === true) !== (server.favorite === true),
      local,
      server,
      local.favorite === true,
      server.favorite === true,
    )
      ? { favorite: true }
      : {}),
  };
}

export function metadataEquivalent(left: Metadata, right: Metadata): boolean {
  return (
    left.schemaVersion === right.schemaVersion &&
    left.takenAtUtc === right.takenAtUtc &&
    left.utcOffsetMinutes === right.utcOffsetMinutes &&
    left.timeZone === right.timeZone &&
    left.note === right.note &&
    left.createdAtUtc === right.createdAtUtc &&
    sameValue(normalizedTags(left.tags), normalizedTags(right.tags)) &&
    sameValue(left.location, right.location) &&
    sameValue(left.media, right.media) &&
    (left.favorite === true) === (right.favorite === true)
  );
}

import type { EntryMetadataV1 } from '@kodakiko/contract';
import { localDateOf, localTimeOf } from '@kodakiko/core';
import type { z } from 'zod';
import type { ObjectRow } from '../../adapters/rowstore/rowstore';
import { EN } from '../../text/en';
import { formatDuration } from '../capture/format-duration';
import { formatBytes } from '../format/format-bytes';

export type DetailRow = { readonly label: string; readonly value: string };
export type DetailSection = { readonly title: string; readonly rows: readonly DetailRow[] };

type EntryFacts = { readonly revision: number; readonly createdAt: string; readonly updatedAt: string; readonly localState: string };
type StoredObject = Pick<ObjectRow, 'variant' | 'sizeBytes' | 'localState' | 'uploadState'>;

const MINUTES_PER_HOUR = 60;
const PAD = 2;
const COORDINATE_DECIMALS = 5;
const DATE_TIME_CHARS = 16;

function offsetText(minutes: number): string {
  const sign = minutes < 0 ? '-' : '+';
  const absolute = Math.abs(minutes);
  const hours = String(Math.floor(absolute / MINUTES_PER_HOUR)).padStart(PAD, '0');
  const rest = String(absolute % MINUTES_PER_HOUR).padStart(PAD, '0');
  return `UTC${sign}${hours}:${rest}`;
}

function utcText(iso: string): string {
  return `${iso.slice(0, DATE_TIME_CHARS).replace('T', ' ')} UTC`;
}

function stateText(object: StoredObject | undefined): string {
  if (object === undefined) return EN['details.unknown'];
  const local = object.localState === 'present' ? EN['details.onDevice'] : EN['details.notOnDevice'];
  const upload = object.uploadState === 'uploaded' ? EN['details.uploaded'] : EN['details.notUploaded'];
  return `${local}, ${upload}`;
}

export function entryDetailSections(input: {
  readonly entryId: string;
  readonly metadata: z.infer<typeof EntryMetadataV1>;
  readonly row: EntryFacts | null;
  readonly objects: ReadonlyMap<string, readonly StoredObject[]>;
  readonly technical: boolean;
}): readonly DetailSection[] {
  const { metadata, row, technical } = input;
  const local = `${localDateOf(metadata.takenAtUtc, metadata.utcOffsetMinutes)} ${localTimeOf(metadata.takenAtUtc, metadata.utcOffsetMinutes)}`;
  const zone = metadata.timeZone === null ? offsetText(metadata.utcOffsetMinutes) : `${offsetText(metadata.utcOffsetMinutes)}, ${metadata.timeZone}`;
  const entryRows: DetailRow[] = [];
  if (technical) entryRows.push({ label: EN['details.id'], value: input.entryId });
  entryRows.push({ label: EN['details.taken'], value: `${local} (${zone})` });
  if (row !== null) {
    entryRows.push(
      { label: EN['details.created'], value: utcText(row.createdAt) },
      { label: EN['details.edited'], value: utcText(row.updatedAt) },
      { label: EN['details.version'], value: String(row.revision) },
      { label: EN['details.sync'], value: row.localState },
    );
  }
  if (metadata.location !== null) {
    const { latitude, longitude, source } = metadata.location;
    entryRows.push({ label: EN['details.location'], value: `${latitude.toFixed(COORDINATE_DECIMALS)}, ${longitude.toFixed(COORDINATE_DECIMALS)} (${source})` });
  }
  if (metadata.tags.length > 0) entryRows.push({ label: EN['details.tags'], value: metadata.tags.map((tag) => `#${tag}`).join(' ') });
  entryRows.push({ label: EN['details.noteLength'], value: EN['details.characters'](metadata.note.length) });

  const mediaSections = metadata.media.map((item, index): DetailSection => {
    const stored = input.objects.get(item.mediaId) ?? [];
    const original = stored.find((object) => object.variant === 'original');
    const thumbnail = stored.find((object) => object.variant !== 'original');
    const rows: DetailRow[] = [];
    if (item.originalFileName !== null) rows.push({ label: EN['details.fileName'], value: item.originalFileName });
    rows.push(
      { label: EN['details.type'], value: item.mimeType },
      { label: EN['details.size'], value: formatBytes(item.originalPlaintextBytes) },
      { label: EN['details.dimensions'], value: `${item.width} × ${item.height}` },
    );
    if (item.durationMs !== null) rows.push({ label: EN['details.duration'], value: formatDuration(item.durationMs) });
    if (item.capturedAtUtc !== null) rows.push({ label: EN['details.capturedAt'], value: utcText(item.capturedAtUtc) });
    rows.push({ label: EN['details.original'], value: stateText(original) });
    if (thumbnail !== undefined) rows.push({ label: EN['details.thumbnail'], value: stateText(thumbnail) });
    if (technical) {
      rows.push({ label: EN['details.mediaId'], value: item.mediaId });
      if (original !== undefined) rows.push({ label: EN['details.encryptedSize'], value: formatBytes(original.sizeBytes) });
    }
    const position = index + 1;
    return { title: item.kind === 'video' ? EN['details.video'](position) : EN['details.photo'](position), rows };
  });
  return [{ title: EN['details.entry'], rows: entryRows }, ...mediaSections];
}

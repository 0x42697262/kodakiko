import type { EntryMetadataV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import {
  EXPORT_EXTENSIONS,
  EXPORT_FALLBACK_EXTENSION,
  EXPORT_FILE_OVERHEAD_BYTES,
  EXPORT_MTIME_MAX,
  EXPORT_MTIME_MIN,
  EXPORT_NOTE_FILE,
} from './constants.ts';

type Metadata = z.infer<typeof EntryMetadataV1>;

const MS_PER_MINUTE = 60_000;
const MINUTES_PER_HOUR = 60;
const POSITION_DIGITS = 3;
const PART_DIGITS = 2;
const TWO_DIGITS = 2;
const ID_PREFIX_CHARS = 8;
const COORDINATE_DECIMALS = 5;
const DATE_CHARS = 10;
const TIME_START = 11;
const TIME_END = 19;
const HOUR_MINUTE_CHARS = 5;
const NEEDS_QUOTES = /[:#[\]{},"']|^\s|\s$/;

export type ExportEntryPlan = {
  readonly folder: string;
  readonly noteFile: string;
  readonly noteText: string;
  readonly media: readonly { readonly mediaId: string; readonly fileName: string; readonly plaintextBytes: number }[];
  readonly mtime: Date;
};

export type PlannedFile = { readonly entryId: string; readonly path: string; readonly bytes: number };

function pad(value: number, digits: number): string {
  return String(value).padStart(digits, '0');
}

function localIso(metadata: Metadata): string {
  return new Date(Date.parse(metadata.takenAtUtc) + metadata.utcOffsetMinutes * MS_PER_MINUTE).toISOString();
}

function offsetText(minutes: number): string {
  const sign = minutes < 0 ? '-' : '+';
  const absolute = Math.abs(minutes);
  return `${sign}${pad(Math.floor(absolute / MINUTES_PER_HOUR), TWO_DIGITS)}:${pad(absolute % MINUTES_PER_HOUR, TWO_DIGITS)}`;
}

function mediaFileName(position: number, mimeType: string): string {
  return `${pad(position + 1, POSITION_DIGITS)}.${EXPORT_EXTENSIONS[mimeType] ?? EXPORT_FALLBACK_EXTENSION}`;
}

export function yamlQuote(value: string): string {
  if (value.length > 0 && !NEEDS_QUOTES.test(value)) return value;
  return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`;
}

export function frontMatter(entryId: string, metadata: Metadata): string {
  const local = localIso(metadata);
  const lines = [
    '---',
    `id: ${entryId}`,
    `date: ${local.slice(0, DATE_CHARS)}T${local.slice(TIME_START, TIME_END)}${offsetText(metadata.utcOffsetMinutes)}`,
    `tags: [${metadata.tags.map(yamlQuote).join(', ')}]`,
  ];
  if (metadata.favorite === true) lines.push('favorite: true');
  if (metadata.location !== null) {
    const { latitude, longitude, source } = metadata.location;
    lines.push(
      `location: { latitude: ${latitude.toFixed(COORDINATE_DECIMALS)}, longitude: ${longitude.toFixed(COORDINATE_DECIMALS)}, source: ${source} }`,
    );
  }
  const media = metadata.media.map((item, position) => {
    const parts = [`file: ${mediaFileName(position, item.mimeType)}`];
    if (item.originalFileName !== null) parts.push(`original_name: ${yamlQuote(item.originalFileName)}`);
    parts.push(`kind: ${item.kind}`);
    if (item.caption !== undefined) parts.push(`caption: ${yamlQuote(item.caption)}`);
    return `{ ${parts.join(', ')} }`;
  });
  lines.push(`media: [${media.join(', ')}]`, '---', '');
  return lines.join('\n');
}

export function planEntryExport(entryId: string, metadata: Metadata): ExportEntryPlan {
  const local = localIso(metadata);
  const [year = '', month = '', day = ''] = local.slice(0, DATE_CHARS).split('-');
  const time = local.slice(TIME_START, TIME_START + HOUR_MINUTE_CHARS).replace(':', '');
  const folder = `${year}/${month}/${year}-${month}-${day}_${time}_${entryId.slice(0, ID_PREFIX_CHARS)}/`;
  return {
    folder,
    noteFile: `${folder}${EXPORT_NOTE_FILE}`,
    noteText: `${frontMatter(entryId, metadata)}\n${metadata.note}\n`,
    media: metadata.media.map((item, position) => ({
      mediaId: item.mediaId,
      fileName: mediaFileName(position, item.mimeType),
      plaintextBytes: item.originalPlaintextBytes,
    })),
    mtime: new Date(metadata.takenAtUtc),
  };
}

export function planParts(
  files: readonly PlannedFile[],
  partMaxBytes: number,
  partMaxFiles: number,
): readonly (readonly PlannedFile[])[] {
  const parts: PlannedFile[][] = [];
  let current: PlannedFile[] = [];
  let bytes = 0;
  for (const file of files) {
    const fits = bytes + file.bytes + EXPORT_FILE_OVERHEAD_BYTES <= partMaxBytes && current.length < partMaxFiles;
    if (!fits && current.length > 0) {
      parts.push(current);
      current = [];
      bytes = 0;
    }
    current.push(file);
    bytes += file.bytes + EXPORT_FILE_OVERHEAD_BYTES;
  }
  if (current.length > 0) parts.push(current);
  return parts;
}

export function partFileName(exportDate: string, partNumber: number): string {
  return `kodakiko-export-${exportDate}-part${pad(partNumber, PART_DIGITS)}.zip`;
}

export function clampZipDate(date: Date): Date {
  if (date.getTime() < EXPORT_MTIME_MIN.getTime()) return EXPORT_MTIME_MIN;
  if (date.getTime() > EXPORT_MTIME_MAX.getTime()) return EXPORT_MTIME_MAX;
  return date;
}

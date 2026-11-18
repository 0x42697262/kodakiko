import { EntryMetadataV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import { bytesEqual, concatBytes, type Sodium } from '../../src/crypto/index.ts';
import { counterBytes, createCounterRandom } from '../../src/crypto/deterministic-bytes.ts';
import {
  EXPORT_EXTENSIONS,
  EXPORT_FALLBACK_EXTENSION,
  EXPORT_FILE_OVERHEAD_BYTES,
  EXPORT_MISSING_FILE,
  EXPORT_MTIME_MAX,
  EXPORT_MTIME_MIN,
  EXPORT_NOTE_FILE,
  EXPORT_PART_MAX_BYTES,
  EXPORT_PART_MAX_FILES,
  EXPORT_README_FILE,
  EXPORT_TEXT,
  EXPORT_WEB_MEMORY_PART_MAX_BYTES,
  KDKX_CHUNK_BYTES,
  KDKX_EXTENSION,
  KDKX_SALT_BYTES,
  clampZipDate,
  createKdkxWriter,
  decryptKdkx,
  deriveKdkxKey,
  frontMatter,
  isKdkx,
  parseEntryNote,
  partFileName,
  planEntryExport,
  planParts,
  readKdkxParams,
  yamlQuote,
  type KdkxParams,
  type PlannedFile,
} from '../../src/export/index.ts';
import { functionCase, outcome, sha256Hex, toJson, type GoldenCase, type GoldenWriter, type Json } from './shared.ts';

type Metadata = z.infer<typeof EntryMetadataV1>;
type MediaItem = Metadata['media'][number];

const PATHS = Object.freeze({
  layout: 'export/layout.json',
  parseEntry: 'export/parse-entry.json',
  kdkx: 'export/kdkx.json',
  zip: 'export/zip.json',
});

const KDKX_DIRECTORY = 'export/kdkx';
const KDKX_OPS_LIMIT = 2;
const KDKX_MEM_LIMIT_BYTES = 8_388_608;
const KDKX_PASSPHRASE = 'a long export passphrase';
const KDKX_WRONG_PASSPHRASE = 'not the passphrase';
const KDKX_PREFIX_BYTES = 29;
const KDKX_STREAM_HEADER_BYTES = 24;
const KDKX_HEADER_BYTES = KDKX_PREFIX_BYTES + KDKX_STREAM_HEADER_BYTES;
const AEAD_TAG = 16;
const KDKX_MIN_FILE_BYTES = KDKX_HEADER_BYTES + AEAD_TAG;
const FULL_CHUNK_CIPHERTEXT = KDKX_CHUNK_BYTES + AEAD_TAG;
const UNEVEN_FIRST_PIECE = 3;
const TWO_CHUNKS_PLUS_SEVEN = KDKX_CHUNK_BYTES * 2 + 7;
const CHUNK_PLUS_TEN = KDKX_CHUNK_BYTES + 10;
const FLIP_FROM_END = 20;
const TRUNCATE_BY = KDKX_CHUNK_BYTES / 2;
const SHORT_TAIL = 10;
const VERSION_OFFSET = 4;
const OPS_OFFSET = 5;
const MEM_OFFSET = 9;
const STREAM_MAGIC_OFFSET = KDKX_PREFIX_BYTES;
const PREFIX_SALT_OFFSET = 13;
const MEDIA_ID_DIGITS = 21;
const MEDIA_BYTES_UNIT = 1000;

const ENTRY_ID = 'AbCdEfGh12345678901234';
const ZERO_MEDIA_ID = 'mmmmmmmmmmmmmmmmmmmm00';

function layoutMedia(mimeType: string, kind: MediaItem['kind'], index: number): MediaItem {
  return {
    mediaId: `M${String(index).padStart(MEDIA_ID_DIGITS, '0')}`,
    kind,
    mimeType,
    width: 10,
    height: 10,
    durationMs: kind === 'photo' ? null : 1000,
    originalPlaintextBytes: MEDIA_BYTES_UNIT * (index + 1),
    thumbnail: { width: 4, height: 4, mimeType: 'image/webp', plaintextBytes: 10 },
    capturedAtUtc: null,
    originalFileName: index === 0 ? 'IMG_1234.JPG' : null,
  } as MediaItem;
}

function layoutMetadata(overrides: Partial<Metadata> = {}): Metadata {
  return {
    schemaVersion: 1,
    takenAtUtc: '2026-11-11T13:05:00.000Z',
    utcOffsetMinutes: 60,
    timeZone: null,
    note: 'Lunch at the beach.',
    tags: ['beach', 'family'],
    location: { latitude: 37.3861, longitude: -122.0839, accuracyMeters: null, source: 'exif' },
    media: [layoutMedia('image/jpeg', 'photo', 0), layoutMedia('video/mp4', 'video', 1), layoutMedia('video/quicktime', 'video', 2)],
    createdAtUtc: '2026-11-11T13:06:00.000Z',
    editedAtUtc: '2026-11-11T13:06:00.000Z',
    ...overrides,
  } as Metadata;
}

function requireValid(metadata: Metadata, what: string): Metadata {
  if (!EntryMetadataV1.safeParse(metadata).success) throw new Error(`golden export: invalid metadata in ${what}`);
  return metadata;
}

function entryArgs(entryId: string, metadata: Metadata): Json {
  return { entryId, metadata: toJson(metadata) };
}

function planCase(name: string, entryId: string, metadata: Metadata, validate = true): GoldenCase {
  if (validate) requireValid(metadata, name);
  else if (EntryMetadataV1.safeParse(metadata).success) throw new Error(`golden export: metadata unexpectedly valid in ${name}`);
  const extra = validate ? {} : { metadataValid: false };
  return functionCase(`planEntryExport: ${name}`, 'planEntryExport', entryArgs(entryId, metadata), () => planEntryExport(entryId, metadata), extra);
}

function frontMatterCase(name: string, entryId: string, metadata: Metadata): GoldenCase {
  const value = requireValid(metadata, name);
  return functionCase(`frontMatter: ${name}`, 'frontMatter', entryArgs(entryId, value), () => frontMatter(entryId, value));
}

const ALL_MIME_TYPES: readonly [string, MediaItem['kind']][] = [
  ['image/jpeg', 'photo'],
  ['image/png', 'photo'],
  ['image/webp', 'photo'],
  ['image/heic', 'photo'],
  ['image/heif', 'photo'],
  ['video/mp4', 'video'],
  ['video/quicktime', 'video'],
  ['video/webm', 'video'],
  ['audio/mp4', 'audio'],
  ['audio/webm', 'audio'],
  ['audio/ogg', 'audio'],
  ['image/gif', 'photo'],
  ['IMAGE/JPEG', 'photo'],
  ['', 'photo'],
];

function layoutCases(): GoldenCase[] {
  const base = layoutMetadata();
  const [first, second] = base.media;
  if (first === undefined || second === undefined) throw new Error('golden export: layout media');
  const quotedMedia: MediaItem[] = [
    { ...first, originalFileName: 'a: b {c}.jpg', caption: 'Sunset, "late" \\ 🌅' },
    { ...second, kind: 'audio', mimeType: 'audio/webm', originalFileName: 'plain.weba', caption: 'plain caption' },
  ];
  const cases: GoldenCase[] = [
    planCase('names the folder from the local time and numbers media with extensions from the MIME type', ENTRY_ID, base),
    planCase(
      'crosses midnight and months by the entry offset (into the next year)',
      ENTRY_ID,
      layoutMetadata({ takenAtUtc: '2026-12-31T23:30:00.000Z', utcOffsetMinutes: 120 }),
    ),
    planCase(
      'crosses midnight and months by the entry offset (back into February)',
      ENTRY_ID,
      layoutMetadata({ takenAtUtc: '2026-03-01T02:00:00.000Z', utcOffsetMinutes: -300 }),
    ),
    planCase('writes the note after the front matter in entry.md', ENTRY_ID, base),
    planCase(
      'edge: every known MIME type, then the bin fallback for unknown, upper-case and empty types',
      ENTRY_ID,
      layoutMetadata({ media: ALL_MIME_TYPES.map(([mimeType, kind], index) => layoutMedia(mimeType, kind, index)) }),
    ),
    planCase('edge: an entry id shorter than eight characters', 'ENTRY1', base),
    planCase('edge: an offset of +05:45', ENTRY_ID, layoutMetadata({ utcOffsetMinutes: 345 })),
    planCase('edge: an offset of -03:30', ENTRY_ID, layoutMetadata({ utcOffsetMinutes: -210 })),
    planCase('edge: an offset of +14:00 into the next day', ENTRY_ID, layoutMetadata({ takenAtUtc: '2026-11-11T13:05:00.000Z', utcOffsetMinutes: 840 })),
    planCase('edge: an offset of -12:00', ENTRY_ID, layoutMetadata({ utcOffsetMinutes: -720 })),
    planCase('edge: a zero offset is written as +00:00', ENTRY_ID, layoutMetadata({ utcOffsetMinutes: 0 })),
    planCase('edge: a takenAtUtc without milliseconds', ENTRY_ID, layoutMetadata({ takenAtUtc: '2026-11-11T13:05:00Z' })),
    planCase(
      'edge: seconds are kept and fractions dropped in the date line, the folder uses hours and minutes only',
      ENTRY_ID,
      layoutMetadata({ takenAtUtc: '2026-11-11T13:05:59.999Z', utcOffsetMinutes: 0 }),
    ),
    planCase('edge: a takenAtUtc with an explicit offset', ENTRY_ID, layoutMetadata({ takenAtUtc: '2026-11-11T15:05:00+02:00' }), false),
    planCase('edge: an empty note', ENTRY_ID, layoutMetadata({ note: '' })),
    planCase('edge: a note that already ends with a newline and has CRLF lines', ENTRY_ID, layoutMetadata({ note: 'one\r\ntwo\n' })),
    planCase('edge: a note with a front matter fence inside', ENTRY_ID, layoutMetadata({ note: '---\nid: fake\n---' })),
    planCase('edge: a favorite entry', ENTRY_ID, layoutMetadata({ favorite: true })),
    planCase(
      'edge: a local time before the year 0000 gives an extended-year folder',
      ENTRY_ID,
      layoutMetadata({ takenAtUtc: '0000-01-01T00:30:00.000Z', utcOffsetMinutes: -60 }),
    ),
    planCase('edge: an unparsable takenAtUtc throws', ENTRY_ID, layoutMetadata({ takenAtUtc: 'not a date' }), false),
    frontMatterCase('writes id, local date with offset, tags, location and media', ENTRY_ID, base),
    frontMatterCase(
      'omits a missing location and quotes awkward tags',
      ENTRY_ID,
      layoutMetadata({ location: null, tags: ['a: b', 'say "hi"', ' padded', '🌊 sea', 'back\\slash'] }),
    ),
    frontMatterCase('edge: favorite true is written, favorite false is omitted (true)', ENTRY_ID, layoutMetadata({ favorite: true })),
    frontMatterCase('edge: favorite true is written, favorite false is omitted (false)', ENTRY_ID, layoutMetadata({ favorite: false })),
    frontMatterCase('edge: no tags', ENTRY_ID, layoutMetadata({ tags: [] })),
    frontMatterCase(
      'edge: coordinates are rounded with toFixed(5), keeping the sign of tiny negatives',
      ENTRY_ID,
      layoutMetadata({ location: { latitude: -0.000001, longitude: 1.000005, accuracyMeters: 3, source: 'device' } }),
    ),
    frontMatterCase(
      'edge: coordinates whose binary value lies just below a half round down, others round up to the next integer',
      ENTRY_ID,
      layoutMetadata({ location: { latitude: 0.0000025, longitude: 179.999995, accuracyMeters: null, source: 'exif' } }),
    ),
    frontMatterCase(
      'edge: coordinates at the limits and zero',
      ENTRY_ID,
      layoutMetadata({ location: { latitude: -90, longitude: 0, accuracyMeters: null, source: 'exif' } }),
    ),
    frontMatterCase('edge: quoted original names and captions, an audio item', ENTRY_ID, layoutMetadata({ media: quotedMedia })),
    frontMatterCase(
      'edge: whitespace rules for tags (inner newline not quoted, leading tab and no-break space quoted)',
      ENTRY_ID,
      layoutMetadata({ tags: ['line\nbreak', '\ttab', '\u00a0nbsp', 'end\u2028', 'mid space'] }),
    ),
    frontMatterCase('edge: a negative offset with minutes', ENTRY_ID, layoutMetadata({ utcOffsetMinutes: -570 })),
  ];
  const quotes: readonly [string, string][] = [
    ['plain', 'plain'],
    ['a:b', '"a:b"'],
    ['#tag', '"#tag"'],
    ['[x]', '"[x]"'],
    ["it's", '"it\'s"'],
    ['', '""'],
    ['trailing ', '"trailing "'],
    ['q"\\', '"q\\"\\\\"'],
  ];
  for (const [input, expected] of quotes) {
    const goldenCase = functionCase(`yamlQuote: ${JSON.stringify(input)} → ${expected}`, 'yamlQuote', { value: input }, () => yamlQuote(input));
    if (goldenCase['result'] !== expected) throw new Error(`golden export: yamlQuote ${input}`);
    cases.push(goldenCase);
  }
  const quoteEdges: readonly [string, string][] = [
    ['an opening brace', '{a'],
    ['a closing brace', 'a}'],
    ['a comma', 'a,b'],
    ['a closing bracket', 'a]'],
    ['a lone double quote', '"'],
    ['a backslash alone is not a reason to quote', 'a\\b'],
    ['a leading space', ' a'],
    ['a leading tab', '\ta'],
    ['a trailing newline', 'a\n'],
    ['an inner newline is not quoted or escaped', 'a\nb'],
    ['a leading no-break space', '\u00a0a'],
    ['a leading byte order mark', '\ufeffa'],
    ['a trailing line separator', 'a\u2028'],
    ['a leading next-line U+0085 is not whitespace', '\u0085a'],
    ['a leading zero-width space is not whitespace', '\u200ba'],
    ['a leading dash is not quoted', '- item'],
    ['YAML keywords are not quoted', 'true'],
    ['numbers are not quoted', '123'],
    ['emoji and non-ASCII are not quoted', '🌊 Café'],
    ['a single space', ' '],
    ['a percent sign, ampersand, star, pipe and greater-than are not quoted', '%&*|>!@`'],
    ['a full-width colon is not quoted', 'a：b'],
  ];
  for (const [name, input] of quoteEdges) {
    cases.push(functionCase(`yamlQuote: edge: ${name}`, 'yamlQuote', { value: input }, () => yamlQuote(input)));
  }
  cases.push(
    functionCase('EXPORT_TEXT: edge: readme', 'EXPORT_TEXT.readme', { date: '2026-11-11' }, () => EXPORT_TEXT.readme('2026-11-11')),
    functionCase('EXPORT_TEXT: edge: missingHeader', 'EXPORT_TEXT.missingHeader', {}, () => EXPORT_TEXT.missingHeader),
    functionCase(
      'EXPORT_TEXT: edge: missingLine',
      'EXPORT_TEXT.missingLine',
      { folder: '2026/11/2026-11-11_1405_AbCdEfGh/', file: '002.mp4', reason: 'The file could not be downloaded.' },
      () => EXPORT_TEXT.missingLine('2026/11/2026-11-11_1405_AbCdEfGh/', '002.mp4', 'The file could not be downloaded.'),
    ),
    functionCase('constants: edge: layout constants', 'constants', {}, () => ({
      EXPORT_NOTE_FILE,
      EXPORT_README_FILE,
      EXPORT_MISSING_FILE,
      EXPORT_FALLBACK_EXTENSION,
      EXPORT_EXTENSIONS,
    })),
  );
  return cases;
}

const PARSE_MEDIA = {
  kind: 'photo',
  mimeType: 'image/jpeg',
  width: 10,
  height: 10,
  durationMs: null,
  originalPlaintextBytes: 100,
  thumbnail: { width: 10, height: 10, mimeType: 'image/webp', plaintextBytes: 10 },
  capturedAtUtc: null,
} as const;

function parseMetadata(patch: Record<string, unknown> = {}): Metadata {
  return EntryMetadataV1.parse({
    schemaVersion: 1,
    takenAtUtc: '2026-11-01T07:30:00.000Z',
    utcOffsetMinutes: -330,
    timeZone: null,
    note: '# Title\n\nA line with: colons, "quotes" and #hash.\n---\nnot front matter',
    tags: ['beach', 'trip-2026'],
    location: { latitude: 52.52, longitude: -13.405, accuracyMeters: null, source: 'exif' },
    media: [
      { ...PARSE_MEDIA, mediaId: 'mmmmmmmmmmmmmmmmmmmm01', originalFileName: 'IMG "1", final.jpg' },
      { ...PARSE_MEDIA, mediaId: 'mmmmmmmmmmmmmmmmmmmm02', kind: 'video', mimeType: 'video/mp4', durationMs: 1000, originalFileName: null },
    ],
    createdAtUtc: '2026-11-01T07:30:00.000Z',
    editedAtUtc: '2026-11-01T07:30:00.000Z',
    ...patch,
  });
}

function parseCase(name: string, source: string): GoldenCase {
  return functionCase(`parseEntryNote: ${name}`, 'parseEntryNote', { source }, () => parseEntryNote(source));
}

const VALID_HEAD = '---\nid: X\ndate: 2026-11-01T02:00:00+00:00\n';
const MINIMAL_NOTE = `${VALID_HEAD}---\n\nbody\n`;

function parseEntryCases(): GoldenCase[] {
  const captionMetadata = parseMetadata({
    media: [
      { ...PARSE_MEDIA, mediaId: 'mmmmmmmmmmmmmmmmmmmm01', originalFileName: null, caption: 'Sunset, "late"' },
      { ...PARSE_MEDIA, mediaId: 'mmmmmmmmmmmmmmmmmmmm03', kind: 'audio', mimeType: 'audio/mp4', durationMs: 4000, originalFileName: null },
    ],
  });
  const roundTrips: readonly [string, string, Metadata][] = [
    ['reads back everything the exporter wrote', 'ENTRY1', parseMetadata({ favorite: true })],
    ['handles no tags, no location, no favorite and an empty note', 'ENTRY2', parseMetadata({ tags: [], location: null, note: '' })],
    ['writes captions and reads them back with audio items', 'ENTRY3', captionMetadata],
    [
      'edge: reads back awkward tags, quoted names and captions with escapes',
      'ENTRY4',
      parseMetadata({
        tags: ['a: b', 'say "hi"', ' padded', 'back\\slash', '🌊'],
        media: [{ ...PARSE_MEDIA, mediaId: ZERO_MEDIA_ID, originalFileName: 'x{y}.jpg', caption: 'c\\"d' }],
      }),
    ],
    ['edge: a note with leading and trailing blank lines', 'ENTRY5', parseMetadata({ note: '\n\nmiddle\n\n' })],
    ['edge: a positive offset with minutes and a negative coordinate rounding', 'ENTRY6', parseMetadata({ utcOffsetMinutes: 345, location: { latitude: -0.000001, longitude: 1.000005, accuracyMeters: null, source: 'device' } })],
  ];
  const cases: GoldenCase[] = [];
  for (const [name, entryId, metadata] of roundTrips) {
    const plan = planEntryExport(entryId, metadata);
    cases.push(
      functionCase(`planEntryExport: ${name}`, 'planEntryExport', entryArgs(entryId, metadata), () => planEntryExport(entryId, metadata)),
      parseCase(name, plan.noteText),
    );
  }
  cases.push(
    parseCase('rejects text without front matter', 'just a note'),
    parseCase('edge: the minimal accepted note (no tags, media or location keys)', MINIMAL_NOTE),
    parseCase('edge: CRLF line endings are rejected', MINIMAL_NOTE.replaceAll('\n', '\r\n')),
    parseCase('edge: a missing closing fence', `${VALID_HEAD}body\n`),
    parseCase('edge: a closing fence at the very end without a newline', `${VALID_HEAD}---`),
    parseCase('edge: a closing fence followed by end of text', `${VALID_HEAD}---\n`),
    parseCase('edge: an empty front matter has no date', '---\n---\nbody'),
    parseCase('edge: a missing date', '---\nid: X\n---\n\nbody\n'),
    parseCase('edge: a date without an offset', '---\nid: X\ndate: 2026-11-01T02:00:00Z\n---\n\nbody\n'),
    parseCase('edge: a date with milliseconds', '---\nid: X\ndate: 2026-11-01T02:00:00.000+00:00\n---\n\nbody\n'),
    parseCase('edge: an offset of -00:00', '---\nid: X\ndate: 2026-11-01T02:00:00-00:00\n---\n\nbody\n'),
    parseCase('edge: offset minutes above 59 are accepted', '---\nid: X\ndate: 2026-11-01T02:00:00+01:99\n---\n\nbody\n'),
    parseCase('edge: February 30 rolls over into March', '---\nid: X\ndate: 2026-02-30T12:00:00+00:00\n---\n\nbody\n'),
    parseCase('edge: hour 24:00:00 means the next midnight', '---\nid: X\ndate: 2026-01-01T24:00:00+00:00\n---\n\nbody\n'),
    parseCase('edge: day 32 throws a RangeError from toISOString', '---\nid: X\ndate: 2026-01-32T12:00:00+00:00\n---\n\nbody\n'),
    parseCase('edge: minute 60 throws a RangeError from toISOString', '---\nid: X\ndate: 2026-01-01T12:60:00+00:00\n---\n\nbody\n'),
    parseCase('edge: a missing id', '---\ndate: 2026-11-01T02:00:00+00:00\n---\n\nbody\n'),
    parseCase('edge: an empty id is kept', `---\nid:\ndate: 2026-11-01T02:00:00+00:00\n---\n\nbody\n`),
    parseCase('edge: keys and values are trimmed, the last duplicate wins, lines without a key are skipped', '---\n  id :  first  \nid: second\n: orphan\nno colon\ndate:2026-11-01T02:00:00+00:00\n---\n\nbody\n'),
    parseCase('edge: tags that are not a list', `${VALID_HEAD}tags: beach\n---\n\nbody\n`),
    parseCase('edge: an empty tags value is not a list', `${VALID_HEAD}tags:\n---\n\nbody\n`),
    parseCase('edge: an empty location value is null', `${VALID_HEAD}location:\n---\n\nbody\n`),
    parseCase('edge: trailing text after a list is ignored, empty items are kept', `${VALID_HEAD}tags: [ , x] junk\n---\n\nbody\n`),
    parseCase('edge: a tab is not skipped but trimmed from an unquoted scalar', `${VALID_HEAD}tags: [\ta]\n---\n\nbody\n`),
    parseCase('edge: tags with nested lists', `${VALID_HEAD}tags: [a, [b]]\n---\n\nbody\n`),
    parseCase('edge: unquoted tags are trimmed, quoted tags unescaped', `${VALID_HEAD}tags: [  spaced  ,"q\\"uote", "back\\\\slash" , "a\\nb"]\n---\n\nbody\n`),
    parseCase('edge: an unterminated quoted tag', `${VALID_HEAD}tags: ["open]\n---\n\nbody\n`),
    parseCase('edge: a list without a closing bracket', `${VALID_HEAD}tags: [a, b\n---\n\nbody\n`),
    parseCase('edge: a list item separated by a semicolon', `${VALID_HEAD}tags: [a; b]\n---\n\nbody\n`),
    parseCase('edge: favorite other than true is false', `${VALID_HEAD}favorite: yes\n---\n\nbody\n`),
    parseCase('edge: a location that is a plain string is null', `${VALID_HEAD}location: somewhere\n---\n\nbody\n`),
    parseCase('edge: a location that is a list is null', `${VALID_HEAD}location: [1, 2]\n---\n\nbody\n`),
    parseCase('edge: a location with a non-numeric latitude gives NaN', `${VALID_HEAD}location: { latitude: north, longitude: 1e2, source: x }\n---\n\nbody\n`),
    parseCase('edge: a location number with spaces and hex', `${VALID_HEAD}location: { latitude: 0x10, longitude: , source: exif }\n---\n\nbody\n`),
    parseCase('edge: a location without a source', `${VALID_HEAD}location: { latitude: 1, longitude: 2 }\n---\n\nbody\n`),
    parseCase('edge: a map without a colon', `${VALID_HEAD}location: { latitude }\n---\n\nbody\n`),
    parseCase('edge: a media item with an unknown kind', `${VALID_HEAD}media: [{ file: 001.pdf, kind: document }]\n---\n\nbody\n`),
    parseCase('edge: a media item that is not a map', `${VALID_HEAD}media: [001.jpg]\n---\n\nbody\n`),
    parseCase('edge: a media item without a file', `${VALID_HEAD}media: [{ kind: photo }]\n---\n\nbody\n`),
    parseCase('edge: a media item with extra keys and an empty caption', `${VALID_HEAD}media: [{ file: a.jpg, kind: video, extra: 1, caption: "" }]\n---\n\nbody\n`),
    parseCase('edge: a body without the blank line after the fence', `${VALID_HEAD}---\nbody\n\n`),
    parseCase('edge: a body without a trailing newline', `${VALID_HEAD}---\n\nbody`),
    parseCase('edge: only the first closing fence ends the front matter', `${VALID_HEAD}---\n\na\n---\nb\n`),
    parseCase('edge: a leading byte order mark is rejected', `\ufeff${MINIMAL_NOTE}`),
  );
  return cases;
}

type KdkxOperation = { readonly op: 'push'; readonly size: number } | { readonly op: 'finish' };

type KdkxWriteSpec = {
  readonly name: string;
  readonly passphrase: string;
  readonly randomSeed: number;
  readonly plaintextSeed: number;
  readonly plaintextSize: number;
  readonly operations: readonly KdkxOperation[];
  readonly store: string | null;
};

type KdkxWritten = { readonly spec: KdkxWriteSpec; readonly bytes: Uint8Array; readonly fileRef: Json | null };

type Mutation =
  | { readonly op: 'xor'; readonly offset: number; readonly value: number }
  | { readonly op: 'truncate'; readonly length: number }
  | { readonly op: 'set'; readonly offset: number; readonly bytes: string }
  | { readonly op: 'append'; readonly bytes: string };

function pieces(sizes: readonly number[]): KdkxOperation[] {
  return [...sizes.map((size): KdkxOperation => ({ op: 'push', size })), { op: 'finish' }];
}

function unevenPieces(size: number): KdkxOperation[] {
  return pieces(size === 0 ? [] : [Math.min(size, UNEVEN_FIRST_PIECE), Math.max(0, size - UNEVEN_FIRST_PIECE)]);
}

const ROUND_TRIP_NAME = 'round-trips empty, small, exact-chunk and multi-chunk payloads written in uneven pieces';
const REJECT_NAME = 'rejects a wrong passphrase, a flipped byte and a truncated file';

const KDKX_WRITES: readonly KdkxWriteSpec[] = [
  { name: `${ROUND_TRIP_NAME} (empty)`, passphrase: KDKX_PASSPHRASE, randomSeed: 801, plaintextSeed: 811, plaintextSize: 0, operations: unevenPieces(0), store: 'empty' },
  { name: `${ROUND_TRIP_NAME} (5 bytes)`, passphrase: KDKX_PASSPHRASE, randomSeed: 802, plaintextSeed: 812, plaintextSize: 5, operations: unevenPieces(5), store: 'five-bytes' },
  {
    name: `${ROUND_TRIP_NAME} (exact chunk)`,
    passphrase: KDKX_PASSPHRASE,
    randomSeed: 803,
    plaintextSeed: 813,
    plaintextSize: KDKX_CHUNK_BYTES,
    operations: unevenPieces(KDKX_CHUNK_BYTES),
    store: null,
  },
  {
    name: `${ROUND_TRIP_NAME} (two chunks plus 7 bytes)`,
    passphrase: KDKX_PASSPHRASE,
    randomSeed: 804,
    plaintextSeed: 814,
    plaintextSize: TWO_CHUNKS_PLUS_SEVEN,
    operations: unevenPieces(TWO_CHUNKS_PLUS_SEVEN),
    store: null,
  },
  {
    name: `${REJECT_NAME} (the source file)`,
    passphrase: KDKX_PASSPHRASE,
    randomSeed: 805,
    plaintextSeed: 815,
    plaintextSize: CHUNK_PLUS_TEN,
    operations: pieces([CHUNK_PLUS_TEN]),
    store: 'chunk-plus-10',
  },
  {
    name: 'edge: one piece of exactly one chunk is held back until finish (same bytes as the exact-chunk case)',
    passphrase: KDKX_PASSPHRASE,
    randomSeed: 803,
    plaintextSeed: 813,
    plaintextSize: KDKX_CHUNK_BYTES,
    operations: pieces([KDKX_CHUNK_BYTES]),
    store: null,
  },
  {
    name: 'edge: a chunk is emitted only once more than one chunk is pending',
    passphrase: KDKX_PASSPHRASE,
    randomSeed: 806,
    plaintextSeed: 816,
    plaintextSize: KDKX_CHUNK_BYTES + 1,
    operations: pieces([KDKX_CHUNK_BYTES - 1, 1, 1]),
    store: null,
  },
  {
    name: 'edge: empty pieces and a non-ASCII passphrase without normalization',
    passphrase: 'Pässwört e\u0301 ﬁ 🌸',
    randomSeed: 807,
    plaintextSeed: 817,
    plaintextSize: 5,
    operations: pieces([0, 5, 0]),
    store: 'non-ascii-passphrase',
  },
  {
    name: 'edge: push and finish after finish throw and do not change the output',
    passphrase: KDKX_PASSPHRASE,
    randomSeed: 808,
    plaintextSeed: 818,
    plaintextSize: 6,
    operations: [{ op: 'push', size: 5 }, { op: 'finish' }, { op: 'push', size: 1 }, { op: 'finish' }],
    store: 'after-finish',
  },
];

const KDKX_FAST = Object.freeze({ opsLimit: KDKX_OPS_LIMIT, memLimitBytes: KDKX_MEM_LIMIT_BYTES });

function goldenSodium(base: Sodium, seed: number): { readonly sodium: Sodium; readonly draws: number[] } {
  const counter = createCounterRandom(seed);
  const draws: number[] = [];
  const randomBytes = (length: number): Uint8Array => {
    draws.push(length);
    return counter(length);
  };
  return { sodium: { ...base, randomBytes }, draws };
}

function fileSummary(bytes: Uint8Array): Json {
  return { size: bytes.length, sha256: sha256Hex(bytes) };
}

async function writeKdkx(base: Sodium, writer: GoldenWriter, spec: KdkxWriteSpec): Promise<{ readonly written: KdkxWritten; readonly goldenCase: GoldenCase }> {
  const { sodium, draws } = goldenSodium(base, spec.randomSeed);
  const plaintext = counterBytes(spec.plaintextSeed, spec.plaintextSize);
  const salt = sodium.randomBytes(KDKX_SALT_BYTES);
  const params: KdkxParams = { salt, ...KDKX_FAST };
  const key = await deriveKdkxKey(sodium, spec.passphrase, params);
  const kdkx = createKdkxWriter(sodium, key, params);
  const parts: Uint8Array[] = [kdkx.header];
  const results: Json[] = [];
  const accepted: Uint8Array[] = [];
  let offset = 0;
  for (const operation of spec.operations) {
    if (operation.op === 'push') {
      const piece = plaintext.subarray(offset, offset + operation.size);
      offset += operation.size;
      results.push(
        outcome(
          () => kdkx.push(piece),
          (value) => {
            accepted.push(piece);
            const chunks = value as Uint8Array[];
            parts.push(...chunks);
            return { chunkSizes: chunks.map((chunk) => chunk.length) };
          },
        ),
      );
    } else {
      results.push(
        outcome(
          () => kdkx.finish(),
          (value) => {
            const chunk = value as Uint8Array;
            parts.push(chunk);
            return { chunkSize: chunk.length };
          },
        ),
      );
    }
  }
  const bytes = concatBytes(...parts);
  const fileRef = spec.store === null ? null : await writer.file(KDKX_DIRECTORY, spec.store, bytes, KDKX_EXTENSION);
  const decrypted = decryptKdkx(base, key, bytes);
  if (!bytesEqual(decrypted, concatBytes(...accepted))) throw new Error(`golden export: kdkx round trip ${spec.name}`);
  const goldenCase: GoldenCase = {
    name: `createKdkxWriter: ${spec.name}`,
    fn: 'createKdkxWriter',
    args: {
      passphrase: spec.passphrase,
      kdf: KDKX_FAST,
      randomSeed: spec.randomSeed,
      plaintextSeed: spec.plaintextSeed,
      plaintextSize: spec.plaintextSize,
      operations: spec.operations,
    },
    result: {
      randomDraws: draws,
      salt: toJson(salt),
      key: toJson(key),
      header: toJson(kdkx.header),
      operations: results,
      file: fileRef ?? fileSummary(bytes),
    },
  };
  return { written: { spec, bytes, fileRef }, goldenCase };
}

function hexToBytes(hex: string): Uint8Array {
  const pairs = hex.match(/../g) ?? [];
  return Uint8Array.from(pairs.map((pair) => Number.parseInt(pair, 16)));
}

function mutate(bytes: Uint8Array, mutations: readonly Mutation[]): Uint8Array {
  let out: Uint8Array = bytes.slice();
  for (const mutation of mutations) {
    if (mutation.op === 'xor') out[mutation.offset] = (out[mutation.offset] ?? 0) ^ mutation.value;
    else if (mutation.op === 'truncate') out = out.slice(0, mutation.length);
    else if (mutation.op === 'set') out.set(hexToBytes(mutation.bytes), mutation.offset);
    else out = concatBytes(out, hexToBytes(mutation.bytes));
  }
  return out;
}

function requireWritten(all: readonly KdkxWritten[], name: string): KdkxWritten & { readonly fileRef: Json } {
  const found = all.find((written) => written.spec.name === name);
  if (found === undefined || found.fileRef === null) throw new Error(`golden export: no stored kdkx ${name}`);
  return { ...found, fileRef: found.fileRef };
}

function crafted(opsLimit: number, memLimitBytes: number, version: number, magic: string, length: number): Uint8Array {
  const out = new Uint8Array(length);
  out.set(new TextEncoder().encode(magic), 0);
  out[VERSION_OFFSET] = version;
  const view = new DataView(out.buffer);
  view.setUint32(OPS_OFFSET, opsLimit);
  view.setUint32(MEM_OFFSET, memLimitBytes);
  out.set(counterBytes(PREFIX_SALT_OFFSET, KDKX_SALT_BYTES), PREFIX_SALT_OFFSET);
  return out;
}

async function kdkxCases(base: Sodium, writer: GoldenWriter): Promise<GoldenCase[]> {
  const cases: GoldenCase[] = [];
  const written: KdkxWritten[] = [];
  for (const spec of KDKX_WRITES) {
    const result = await writeKdkx(base, writer, spec);
    written.push(result.written);
    cases.push(result.goldenCase);
  }
  const empty = requireWritten(written, `${ROUND_TRIP_NAME} (empty)`);
  const five = requireWritten(written, `${ROUND_TRIP_NAME} (5 bytes)`);
  const multi = requireWritten(written, `${REJECT_NAME} (the source file)`);
  const stored = [empty, five, multi];

  const derive: readonly [string, string, KdkxParams][] = [
    ['an ASCII passphrase', KDKX_PASSPHRASE, { salt: counterBytes(821, KDKX_SALT_BYTES), ...KDKX_FAST }],
    ['a non-ASCII passphrase is UTF-8 encoded without NFKC', 'Pässwört e\u0301 ﬁ 🌸', { salt: counterBytes(821, KDKX_SALT_BYTES), ...KDKX_FAST }],
    ['an empty passphrase', '', { salt: counterBytes(822, KDKX_SALT_BYTES), ...KDKX_FAST }],
    ['opsLimit 3', KDKX_PASSPHRASE, { salt: counterBytes(821, KDKX_SALT_BYTES), opsLimit: 3, memLimitBytes: KDKX_MEM_LIMIT_BYTES }],
  ];
  for (const [name, passphrase, params] of derive) {
    const key = await deriveKdkxKey(base, passphrase, params);
    cases.push({ name: `deriveKdkxKey: edge: ${name}`, fn: 'deriveKdkxKey', args: { passphrase, params: toJson(params) }, result: toJson(key) });
  }

  for (const item of stored) {
    cases.push(functionCase(`readKdkxParams: edge: ${item.spec.name}`, 'readKdkxParams', { file: item.fileRef }, () => readKdkxParams(item.bytes)));
  }
  const craftedRows: readonly [string, Uint8Array][] = [
    ['edge: the smallest accepted size with big-endian u32 limits is not range-checked', crafted(0xffffffff, 0x01020304, 1, 'KDKX', KDKX_MIN_FILE_BYTES)],
    ['edge: one byte below the smallest size', crafted(KDKX_OPS_LIMIT, KDKX_MEM_LIMIT_BYTES, 1, 'KDKX', KDKX_MIN_FILE_BYTES - 1)],
    ['edge: version 2', crafted(KDKX_OPS_LIMIT, KDKX_MEM_LIMIT_BYTES, 2, 'KDKX', KDKX_MIN_FILE_BYTES)],
    ['edge: version 0', crafted(KDKX_OPS_LIMIT, KDKX_MEM_LIMIT_BYTES, 0, 'KDKX', KDKX_MIN_FILE_BYTES)],
    ['edge: a STREAM object magic', crafted(KDKX_OPS_LIMIT, KDKX_MEM_LIMIT_BYTES, 1, 'KDKS', KDKX_MIN_FILE_BYTES)],
    ['edge: lowercase magic', crafted(KDKX_OPS_LIMIT, KDKX_MEM_LIMIT_BYTES, 1, 'kdkx', KDKX_MIN_FILE_BYTES)],
  ];
  for (const [name, bytes] of craftedRows) {
    cases.push(functionCase(`readKdkxParams: ${name}`, 'readKdkxParams', { bytes: toJson(bytes) }, () => readKdkxParams(bytes)));
  }

  const isRows: readonly [string, Uint8Array][] = [
    ['tells a .kdkx part from a zip', Uint8Array.of(0x50, 0x4b, 0x03, 0x04)],
    ['edge: empty bytes', new Uint8Array(0)],
    ['edge: three magic bytes', new TextEncoder().encode('KDK')],
    ['edge: the magic alone is enough', new TextEncoder().encode('KDKX')],
    ['edge: the magic followed by anything', new TextEncoder().encode('KDKXjunk')],
    ['edge: lowercase magic', new TextEncoder().encode('kdkx')],
    ['edge: a STREAM object header', new TextEncoder().encode('KDKS')],
  ];
  for (const [name, bytes] of isRows) {
    cases.push(functionCase(`isKdkx: ${name}`, 'isKdkx', { bytes: toJson(bytes) }, () => isKdkx(bytes)));
  }
  for (const item of stored) {
    cases.push(functionCase(`isKdkx: edge: ${item.spec.name}`, 'isKdkx', { file: item.fileRef }, () => isKdkx(item.bytes)));
  }

  const decryptCase = async (name: string, item: KdkxWritten & { readonly fileRef: Json }, passphrase: string, mutations: readonly Mutation[]) => {
    const key = await deriveKdkxKey(base, passphrase, readKdkxParams(item.bytes));
    const file = mutate(item.bytes, mutations);
    return functionCase(`decryptKdkx: ${name}`, 'decryptKdkx', { file: item.fileRef, mutations, passphrase, key: toJson(key) }, () =>
      fileSummary(decryptKdkx(base, key, file)),
    );
  };
  for (const item of [...stored, requireWritten(written, 'edge: empty pieces and a non-ASCII passphrase without normalization')]) {
    cases.push(await decryptCase(item.spec.name, item, item.spec.passphrase, []));
  }
  const multiSize = multi.bytes.length;
  const lastChunkStart = KDKX_HEADER_BYTES + FULL_CHUNK_CIPHERTEXT;
  cases.push(
    await decryptCase(`${REJECT_NAME} (wrong passphrase)`, multi, KDKX_WRONG_PASSPHRASE, []),
    await decryptCase(`${REJECT_NAME} (flipped byte)`, multi, KDKX_PASSPHRASE, [{ op: 'xor', offset: multiSize - FLIP_FROM_END, value: 1 }]),
    await decryptCase(`${REJECT_NAME} (truncated)`, multi, KDKX_PASSPHRASE, [{ op: 'truncate', length: multiSize - TRUNCATE_BY }]),
    await decryptCase('edge: truncated at a chunk boundary (the last full chunk is not final)', multi, KDKX_PASSPHRASE, [{ op: 'truncate', length: lastChunkStart }]),
    await decryptCase('edge: a final piece shorter than the tag', multi, KDKX_PASSPHRASE, [{ op: 'truncate', length: lastChunkStart + SHORT_TAIL }]),
    await decryptCase('edge: a file holding only the header is too short', five, KDKX_PASSPHRASE, [{ op: 'truncate', length: KDKX_HEADER_BYTES }]),
    await decryptCase('edge: an appended byte', five, KDKX_PASSPHRASE, [{ op: 'append', bytes: '00' }]),
    await decryptCase('edge: a damaged STREAM magic', five, KDKX_PASSPHRASE, [{ op: 'xor', offset: STREAM_MAGIC_OFFSET, value: 1 }]),
    await decryptCase('edge: a changed version byte', five, KDKX_PASSPHRASE, [{ op: 'set', offset: VERSION_OFFSET, bytes: '02' }]),
    await decryptCase('edge: the KDKX prefix is not authenticated (changed opsLimit, original key)', five, KDKX_PASSPHRASE, [
      { op: 'set', offset: OPS_OFFSET, bytes: '00000009' },
    ]),
    await decryptCase('edge: the STREAM header is authenticated (changed nonce prefix byte)', five, KDKX_PASSPHRASE, [
      { op: 'xor', offset: KDKX_HEADER_BYTES - 1, value: 1 },
    ]),
    await decryptCase('edge: a flipped byte in the first chunk of a multi-chunk file', multi, KDKX_PASSPHRASE, [{ op: 'xor', offset: KDKX_HEADER_BYTES, value: 1 }]),
    await decryptCase('edge: a flipped tag byte of an empty payload', empty, KDKX_PASSPHRASE, [{ op: 'xor', offset: KDKX_MIN_FILE_BYTES - 1, value: 1 }]),
  );
  cases.push(
    functionCase('constants: edge: KDKX constants', 'constants', {}, () => ({ KDKX_EXTENSION, KDKX_CHUNK_BYTES, KDKX_SALT_BYTES })),
  );
  return cases;
}

function planned(entryId: string, path: string, bytes: number): PlannedFile {
  return { entryId, path, bytes };
}

function planPartsCase(name: string, files: readonly PlannedFile[], partMaxBytes: number, partMaxFiles: number): GoldenCase {
  return functionCase(`planParts: ${name}`, 'planParts', { files: toJson(files), partMaxBytes, partMaxFiles }, () =>
    planParts(files, partMaxBytes, partMaxFiles),
  );
}

const TINY_PART = 3 * EXPORT_FILE_OVERHEAD_BYTES;

function zipCases(): GoldenCase[] {
  const greedy = [planned('a', 'a/entry.md', 100), planned('a', 'a/001.jpg', 5000), planned('a', 'a/002.jpg', 5000), planned('b', 'b/entry.md', 100)];
  const counted = Array.from({ length: 5 }, (_, index) => planned('x', `x/${index}`, 1));
  const cases: GoldenCase[] = [
    planPartsCase('splits greedily by bytes and lets an entry span two parts', greedy, 5100 + 2 * EXPORT_FILE_OVERHEAD_BYTES, EXPORT_PART_MAX_FILES),
    planPartsCase('splits by file count', counted, Number.MAX_SAFE_INTEGER, 2),
    planPartsCase('returns no parts for an empty journal', [], 1000, 10),
    planPartsCase('edge: a file exactly filling the part (bytes plus overhead equal the limit) stays', [planned('a', 'a/1', 1000), planned('a', 'a/2', 1)], 1000 + EXPORT_FILE_OVERHEAD_BYTES, 10),
    planPartsCase('edge: one byte over the limit starts a new part', [planned('a', 'a/1', 1001), planned('a', 'a/2', 1)], 1000 + EXPORT_FILE_OVERHEAD_BYTES, 10),
    planPartsCase('edge: a file larger than a part gets a part of its own', [planned('a', 'a/1', 10), planned('a', 'a/big', 1_000_000), planned('a', 'a/2', 10)], TINY_PART, 10),
    planPartsCase('edge: zero-byte files still count the overhead', Array.from({ length: 4 }, (_, index) => planned('z', `z/${index}`, 0)), TINY_PART, 10),
    planPartsCase('edge: a file limit of one', counted.slice(0, 3), Number.MAX_SAFE_INTEGER, 1),
    planPartsCase('edge: a file limit of zero still puts one file in each part', counted.slice(0, 3), Number.MAX_SAFE_INTEGER, 0),
    planPartsCase('edge: a byte limit of zero still puts one file in each part', counted.slice(0, 2), 0, 10),
    planPartsCase('edge: the byte and file limits apply together', [planned('a', 'a/1', 10), planned('a', 'a/2', 10), planned('a', 'a/3', 10), planned('a', 'a/4', 5000)], TINY_PART, 2),
    planPartsCase('edge: the default 3.75 GiB limit with files near 1 GiB', Array.from({ length: 5 }, (_, index) => planned('v', `v/00${index + 1}.mp4`, EXPORT_WEB_MEMORY_PART_MAX_BYTES - EXPORT_FILE_OVERHEAD_BYTES)), EXPORT_PART_MAX_BYTES, EXPORT_PART_MAX_FILES),
    planPartsCase('edge: input order is kept, not sorted', [planned('b', 'b/2', 1), planned('a', 'a/1', 1), planned('b', 'b/1', 1)], Number.MAX_SAFE_INTEGER, 2),
    functionCase('partFileName: names parts with two-digit numbers', 'partFileName', { exportDate: '2026-11-11', partNumber: 3 }, () => partFileName('2026-11-11', 3)),
  ];
  const names: readonly [string, string, number][] = [
    ['edge: part 1', '2026-11-11', 1],
    ['edge: part 10', '2026-11-11', 10],
    ['edge: part 100 has three digits', '2026-11-11', 100],
    ['edge: part 0', '2026-11-11', 0],
    ['edge: a negative part number is not padded past its sign', '2026-11-11', -1],
    ['edge: the date string is used as given', 'any text', 7],
  ];
  for (const [name, exportDate, partNumber] of names) {
    cases.push(functionCase(`partFileName: ${name}`, 'partFileName', { exportDate, partNumber }, () => partFileName(exportDate, partNumber)));
  }
  const dates: readonly [string, string][] = [
    ['clamps ZIP dates into 1980-2099 (before 1980)', '1970-01-01T00:00:00Z'],
    ['clamps ZIP dates into 1980-2099 (after 2099)', '2150-06-01T00:00:00Z'],
    ['clamps ZIP dates into 1980-2099 (inside)', '2026-01-01T00:00:00Z'],
    ['edge: midnight 1980-01-01 is before the noon minimum', '1980-01-01T00:00:00.000Z'],
    ['edge: one millisecond before the minimum', '1980-01-01T11:59:59.999Z'],
    ['edge: exactly the minimum', '1980-01-01T12:00:00.000Z'],
    ['edge: one millisecond after the minimum', '1980-01-01T12:00:00.001Z'],
    ['edge: the last DOS day 2107-12-31 is clamped to 2099', '2107-12-31T23:59:58.000Z'],
    ['edge: after the DOS range (2108)', '2108-01-01T00:00:00.000Z'],
    ['edge: the end of 2099 is after the noon maximum', '2099-12-31T23:59:59.000Z'],
    ['edge: exactly the maximum', '2099-12-31T12:00:00.000Z'],
    ['edge: one millisecond before the maximum', '2099-12-31T11:59:59.999Z'],
    ['edge: 1979-12-31', '1979-12-31T23:59:59.000Z'],
    ['edge: an extended negative year', '-000001-01-01T00:00:00.000Z'],
    ['edge: the latest JS date', '+275760-09-13T00:00:00.000Z'],
    ['edge: an invalid date is returned unchanged', 'not a date'],
  ];
  for (const [name, date] of dates) {
    cases.push(functionCase(`clampZipDate: ${name}`, 'clampZipDate', { date }, () => clampZipDate(new Date(date))));
  }
  cases.push(
    functionCase('constants: splits by file count (EXPORT_PART_MAX_FILES) and the other part constants', 'constants', {}, () => ({
      EXPORT_FILE_OVERHEAD_BYTES,
      EXPORT_PART_MAX_FILES,
      EXPORT_PART_MAX_BYTES,
      EXPORT_WEB_MEMORY_PART_MAX_BYTES,
      EXPORT_MTIME_MIN,
      EXPORT_MTIME_MAX,
    })),
  );
  return cases;
}

export async function writeExportGoldens(writer: GoldenWriter, base: Sodium): Promise<void> {
  await writer.functionCases(PATHS.layout, layoutCases());
  await writer.functionCases(PATHS.parseEntry, parseEntryCases());
  await writer.functionCases(PATHS.kdkx, await kdkxCases(base, writer));
  await writer.functionCases(PATHS.zip, zipCases());
}

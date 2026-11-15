import { EXPORT_MTIME_MAX, EXPORT_MTIME_MIN } from '@kodakiko/core';
import { unzipSync, zipSync, type Zippable } from 'fflate';
import { createFflateArchiveWriter } from '../../src/adapters/archive/fflate-archive-writer';
import type { PartSink } from '../../src/services/export/archive';
import { compareCodeUnits, sha256Hex, withTimeZone, type FileRef, type GoldenCase, type GoldenWriter, type Json } from './shared';

const PATH = 'archive/zips.json';
const FILES_DIRECTORY = 'archive/files';
const CONTENT_EXTENSION = '.bin';
const ZIP_EXTENSION = '.zip';
const ARCHIVE_TIME_ZONE = 'UTC';
const MEBIBYTE = 1_048_576;
const LARGE_FILE_BYTES = MEBIBYTE + 1;
const PHOTO_BYTES = 4096;
const INCOMPRESSIBLE_BYTES = 512;
const XORSHIFT_LEFT = 13;
const XORSHIFT_RIGHT = 17;
const XORSHIFT_LEFT_AGAIN = 5;
const BYTE_MASK = 0xff;
const PHOTO_SEED = 0x6b6f6461;
const INCOMPRESSIBLE_SEED = 0x2026;
const ZIP_SYNC_SEED = 0x5a1b;
const LARGE_FILE_UNIT = 'Kodakiko golden archive line: the quick brown fox jumps over the lazy dog.\n';
const STORED_LEVEL = 0;
const FASTEST_LEVEL = 1;
const BEST_LEVEL = 9;
const UNIX_OS = 3;
const UNIX_FILE_ATTRS = 0o100644 * 0x10000;
const EXTENDED_TIMESTAMP_TAG = 0x5455;
const EXTENDED_TIMESTAMP = Uint8Array.of(1, 0x80, 0x1b, 0x02, 0x6a);
const WRITER_PRODUCER = 'writeArchive';
const ZIP_SYNC_PRODUCER = 'zipSync';
const ZIP_SYNC_MTIME = '2026-11-13T09:30:16.000Z';

const UTF8 = new TextEncoder();

function pseudoRandomBytes(seed: number, length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  let state = seed >>> 0;
  for (let index = 0; index < length; index += 1) {
    state ^= state << XORSHIFT_LEFT;
    state >>>= 0;
    state ^= state >>> XORSHIFT_RIGHT;
    state ^= state << XORSHIFT_LEFT_AGAIN;
    state >>>= 0;
    bytes[index] = state & BYTE_MASK;
  }
  return bytes;
}

function largeFile(): Uint8Array {
  const unit = UTF8.encode(LARGE_FILE_UNIT);
  const bytes = new Uint8Array(LARGE_FILE_BYTES);
  for (let offset = 0; offset < bytes.length; offset += unit.length) bytes.set(unit.subarray(0, bytes.length - offset), offset);
  return bytes;
}

const CONTENTS = Object.freeze({
  note: UTF8.encode('---\nid: AbCdEfGhIjKlMnOpQrStUv\ndate: 2026-11-02T10:00:00+00:00\ntags: [beach]\nmedia: [{ file: 001.jpg, kind: photo }]\n---\n\nA day at the beach. A day at the beach. A day at the beach.\n'),
  photo: pseudoRandomBytes(PHOTO_SEED, PHOTO_BYTES),
  readme: UTF8.encode('Kodakiko export\n\nEach entry is a folder with entry.md and its media.\n'),
  empty: new Uint8Array(0),
  nonAscii: UTF8.encode('Café 東京 🌸 — e\u0301\n'),
  large: largeFile(),
  incompressible: pseudoRandomBytes(INCOMPRESSIBLE_SEED, INCOMPRESSIBLE_BYTES),
});

type ContentName = keyof typeof CONTENTS;
type WriteFile = { readonly name: string; readonly mtime: string; readonly compress: boolean; readonly content: ContentName | Uint8Array };
type WriteSpec = { readonly name: string; readonly zip: string; readonly files: readonly WriteFile[] };

const ENTRY_FOLDER = '2026/11/2026-11-02_1000_AbCdEfGh/';
const ENTRY_TIME = '2026-11-02T10:00:00.000Z';

function mtimeFile(mtime: string, compress: boolean): WriteFile {
  return { name: `mtime/${mtime}.txt`, mtime, compress, content: UTF8.encode(`mtime ${mtime}\n`) };
}

const WRITE_SPECS: readonly WriteSpec[] = [
  {
    name: 'stored and deflated files of one entry plus a README',
    zip: 'entry-stored-and-deflated',
    files: [
      { name: `${ENTRY_FOLDER}entry.md`, mtime: ENTRY_TIME, compress: true, content: 'note' },
      { name: `${ENTRY_FOLDER}001.jpg`, mtime: ENTRY_TIME, compress: false, content: 'photo' },
      { name: 'README.txt', mtime: '2026-11-13T09:30:15.000Z', compress: false, content: 'readme' },
    ],
  },
  {
    name: 'stored files only, as the export writes media',
    zip: 'stored-only',
    files: [
      { name: `${ENTRY_FOLDER}001.jpg`, mtime: ENTRY_TIME, compress: false, content: 'photo' },
      { name: `${ENTRY_FOLDER}Café 🌸.txt`, mtime: '2026-11-02T10:00:01.000Z', compress: false, content: 'nonAscii' },
      { name: 'README.txt', mtime: '2026-11-13T09:30:15.000Z', compress: false, content: 'readme' },
    ],
  },
  {
    name: 'empty files, stored and deflated',
    zip: 'empty-files',
    files: [
      { name: 'empty-stored.txt', mtime: ENTRY_TIME, compress: false, content: 'empty' },
      { name: 'empty-deflated.md', mtime: ENTRY_TIME, compress: true, content: 'empty' },
    ],
  },
  {
    name: 'non-ASCII file names',
    zip: 'non-ascii-names',
    files: [
      { name: '2026/11/Café 東京 🌸/entry.md', mtime: ENTRY_TIME, compress: true, content: 'nonAscii' },
      { name: 'Ünïcødé/ß e\u0301.txt', mtime: ENTRY_TIME, compress: false, content: 'nonAscii' },
    ],
  },
  {
    name: 'a deflated file larger than 1 MiB',
    zip: 'large-deflated',
    files: [{ name: 'large.txt', mtime: ENTRY_TIME, compress: true, content: 'large' }],
  },
  {
    name: 'mtimes before 1980 and after 2099 are clamped, seconds are halved',
    zip: 'mtime-clamping',
    files: [
      mtimeFile('1970-01-01T00:00:00.000Z', false),
      mtimeFile('1979-12-31T23:59:59.999Z', true),
      mtimeFile('1980-01-01T00:00:00.000Z', false),
      mtimeFile('1980-01-01T12:00:00.000Z', true),
      mtimeFile('2026-03-04T05:06:07.890Z', false),
      mtimeFile('2026-12-31T23:59:58.000Z', true),
      mtimeFile('2099-12-31T12:00:00.000Z', false),
      mtimeFile('2099-12-31T23:59:59.000Z', true),
      mtimeFile('2100-01-01T00:00:00.000Z', false),
      mtimeFile('2150-06-01T00:00:00.000Z', true),
    ],
  },
  {
    name: 'a deflated file that does not shrink',
    zip: 'incompressible-deflated',
    files: [{ name: 'noise.bin', mtime: ENTRY_TIME, compress: true, content: 'incompressible' }],
  },
  { name: 'no files', zip: 'no-files', files: [] },
];

function bytesOf(content: ContentName | Uint8Array): Uint8Array {
  return typeof content === 'string' ? CONTENTS[content] : content;
}

function concat(chunks: readonly Uint8Array[]): Uint8Array {
  const out = new Uint8Array(chunks.reduce((sum, chunk) => sum + chunk.length, 0));
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

async function writeArchive(files: readonly WriteFile[]): Promise<Uint8Array> {
  const chunks: Uint8Array[] = [];
  const sink: PartSink = {
    write: async (bytes) => {
      chunks.push(bytes);
    },
    close: async () => undefined,
    abort: async () => undefined,
  };
  const writer = createFflateArchiveWriter(sink);
  for (const file of files) {
    const added = writer.addFile(file.name, { compress: file.compress, mtime: new Date(file.mtime) });
    await added.push(bytesOf(file.content), true);
  }
  await writer.finish();
  return concat(chunks);
}

function readArchive(zip: Uint8Array): Json {
  const entries = unzipSync(zip);
  return Object.keys(entries)
    .sort(compareCodeUnits)
    .map((name) => {
      const bytes = entries[name] ?? new Uint8Array(0);
      return { name, size: bytes.length, sha256: sha256Hex(bytes) };
    });
}

type ZipSyncSpec = { readonly name: string; readonly zip: string; readonly build: () => Uint8Array };

const zipSyncMtime = new Date(ZIP_SYNC_MTIME);

const ZIP_SYNC_SPECS: readonly ZipSyncSpec[] = [
  {
    name: 'zipSync, all files stored (level 0), sizes in the local headers',
    zip: 'zipsync-stored',
    build: () =>
      zipSync({ 'a.txt': CONTENTS.readme, 'b/c.bin': pseudoRandomBytes(ZIP_SYNC_SEED, PHOTO_BYTES) }, { level: STORED_LEVEL, mtime: zipSyncMtime }),
  },
  {
    name: 'zipSync level 9 with a directory entry, a file comment, an extra field, Unix attributes and a non-ASCII name',
    zip: 'zipsync-best-directory-extra',
    build: () => {
      const data: Zippable = {
        folder: { 'Café 東京 🌸.md': CONTENTS.nonAscii },
        'note.md': [CONTENTS.note, { comment: 'a comment ✓', extra: { [EXTENDED_TIMESTAMP_TAG]: EXTENDED_TIMESTAMP } }],
      };
      return zipSync(data, { level: BEST_LEVEL, mtime: zipSyncMtime, os: UNIX_OS, attrs: UNIX_FILE_ATTRS });
    },
  },
  {
    name: 'zipSync with per-file levels, an empty file and a large file at level 1',
    zip: 'zipsync-mixed-levels',
    build: () =>
      zipSync(
        {
          'stored.jpg': [CONTENTS.photo, { level: STORED_LEVEL }],
          'empty.txt': CONTENTS.empty,
          'large.txt': [CONTENTS.large, { level: FASTEST_LEVEL }],
        },
        { level: BEST_LEVEL, mtime: zipSyncMtime },
      ),
  },
];

async function contentRef(writer: GoldenWriter, file: WriteFile): Promise<FileRef> {
  const base = typeof file.content === 'string' ? file.content : file.name;
  return writer.file(FILES_DIRECTORY, base, bytesOf(file.content), CONTENT_EXTENSION);
}

async function archiveCases(writer: GoldenWriter): Promise<GoldenCase[]> {
  const writes: GoldenCase[] = [];
  const reads: GoldenCase[] = [];
  for (const spec of WRITE_SPECS) {
    const files: Json[] = [];
    for (const file of spec.files) {
      files.push({ name: file.name, mtime: file.mtime, compress: file.compress, content: await contentRef(writer, file) });
    }
    const zip = await writeArchive(spec.files);
    const ref = await writer.file(FILES_DIRECTORY, spec.zip, zip, ZIP_EXTENSION);
    writes.push({ name: `writeArchive: ${spec.name}`, fn: 'writeArchive', args: { files }, result: { zip: ref } });
    reads.push({ name: `readArchive: ${spec.name}`, fn: 'readArchive', args: { zip: ref }, producer: WRITER_PRODUCER, result: readArchive(zip) });
  }
  for (const spec of ZIP_SYNC_SPECS) {
    const zip = spec.build();
    const ref = await writer.file(FILES_DIRECTORY, spec.zip, zip, ZIP_EXTENSION);
    reads.push({ name: `readArchive: ${spec.name}`, fn: 'readArchive', args: { zip: ref }, producer: ZIP_SYNC_PRODUCER, result: readArchive(zip) });
  }
  const constants: GoldenCase = {
    name: 'constants',
    fn: 'constants',
    args: {},
    result: { EXPORT_MTIME_MIN: EXPORT_MTIME_MIN.toISOString(), EXPORT_MTIME_MAX: EXPORT_MTIME_MAX.toISOString(), timeZone: ARCHIVE_TIME_ZONE },
  };
  return [...writes, ...reads, constants];
}

export async function writeArchiveGoldens(writer: GoldenWriter): Promise<void> {
  const cases = await withTimeZone(ARCHIVE_TIME_ZONE, () => archiveCases(writer));
  await writer.functionCases(PATH, cases);
}

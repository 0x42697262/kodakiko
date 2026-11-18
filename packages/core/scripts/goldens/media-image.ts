import {
  EXIF_ASCII_MAX_BYTES,
  SNIFF_HEADER_BYTES,
  deviceOffsetMinutes,
  displaySize,
  earliestTime,
  fitWithin,
  parseTiffExif,
  readImageMetadata,
  resolveCaptureTime,
  sniffFormat,
  type CaptureTimeInput,
  type ResolvedTime,
} from '../../src/media/index.ts';
import {
  asciiBytes,
  buildJpeg,
  buildPng,
  buildTiff,
  buildWebp,
  concat,
  exifApp1,
  fillerApp2,
  ftyp,
  jfifApp0,
  seededBytes,
  u32be,
  u32le,
  xmpApp1,
  type Endian,
  type TiffOptions,
} from '../../test/media/builders.ts';
import { bytesToHex } from '../../src/crypto/index.ts';
import {
  asyncFunctionCase,
  contentOf,
  failingSource,
  failingSourceJson,
  functionCase,
  recordingSource,
  type Content,
  type GoldenCase,
  type GoldenWriter,
  type Json,
} from './shared.ts';

export const MEDIA_INPUTS = 'media/inputs';
export const MEDIA_OUTPUTS = 'media/outputs';

const PATHS = Object.freeze({
  sniff: 'media/sniff.json',
  exif: 'media/exif.json',
  captureTime: 'media/capture-time.json',
  geometry: 'media/geometry.json',
});

const FAILING_SIZE = 1000;
const FAILING_MESSAGE = 'disk gone';
const RANDOM_HEADER_SEEDS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
const NOT_JPEG_BYTES = 512;
const NOT_JPEG_SEED = 3;
const SCAN_FILLER_COUNT = 5;
const SCAN_FILLER_BYTES = 65_000;
const JPEG_CUT_BYTES = 60;
const PNG_CUT_BYTES = 60;
const TRUNCATE_TAIL_BYTES = 10;
const IFD0_COUNT_OFFSET = 8;
const OVERSIZED_IFD_COUNT = 600;
const BYTE_MASK = 0xff;
const BITS_PER_BYTE = 8;
const HUGE_ASCII_CHARS = 200_000;
const WRONG_ORDER_BYTE = 0x58;
const WRONG_MAGIC = 43;
const MAGIC_OFFSET = 2;
const ORIENTATION_ROTATE_90 = 6;
const DATE = '2026:05:01 10:00:00';
const OFFSET = '+02:00';
const PARIS = { latitude: 48.8584, longitude: 2.2945 };
const SANTIAGO = { latitude: -33.8688, longitude: -70.6693 };
const MAX_EDGE = 512;
const LANDSCAPE = { width: 4000, height: 3000 };
const ORIENTATION_OUT_OF_RANGE = 9;
const NOW = '2026-11-12T10:00:00.000Z';
const WEBP_RIFF_SIZE = 36;
const JPEG_SCAN_BYTES = 64;
const JPEG_SHORT_SCAN_BYTES = 32;
const JPEG_TINY_SCAN_BYTES = 16;
const PNG_TRUNCATED_HEADER_BYTES = 7;
const LATE_BRAND_PADDING = 13;
const SHORT_FTYP_SIZE = 16;
const MINIMAL_RIFF_SIZE = 4;

export function hex(bytes: Uint8Array): string {
  return bytesToHex(bytes);
}

export async function inputRef(writer: GoldenWriter, name: string, bytes: Uint8Array): Promise<Json> {
  return writer.file(MEDIA_INPUTS, name, bytes);
}

export async function inputContentRef(writer: GoldenWriter, name: string, content: Content): Promise<Json> {
  return writer.content(MEDIA_INPUTS, name, content);
}

function sniffCase(name: string, header: Uint8Array): GoldenCase {
  return functionCase(`sniffFormat: ${name}`, 'sniffFormat', { header: hex(header) }, () => sniffFormat(header));
}

function sniffCases(): GoldenCase[] {
  const jpeg = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46]);
  const png = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d]);
  const webm = Uint8Array.from([0x1a, 0x45, 0xdf, 0xa3, 0x9f, 0x42, 0x86, 0x81]);
  const webp = concat(asciiBytes('RIFF'), u32le(WEBP_RIFF_SIZE), asciiBytes('WEBP'), asciiBytes('VP8 '));
  const header = (bytes: Uint8Array) => bytes.slice(0, SNIFF_HEADER_BYTES);
  const cases: GoldenCase[] = [
    functionCase('constant: reads a window of 64 bytes', 'SNIFF_HEADER_BYTES', {}, () => SNIFF_HEADER_BYTES),
    sniffCase('recognizes JPEG', jpeg),
    sniffCase('recognizes PNG', png),
    sniffCase('recognizes WebP', webp),
    sniffCase('refuses a RIFF file that is not WebP', concat(asciiBytes('RIFF'), u32le(WEBP_RIFF_SIZE), asciiBytes('AVI '), asciiBytes('LIST'))),
  ];
  for (const brand of ['heic', 'heix', 'heim', 'heis']) {
    cases.push(sniffCase(`recognizes a HEIF file with major brand ${brand} as image/heic`, header(ftyp(brand, [brand]))));
  }
  for (const brand of ['hevc', 'hevx', 'mif1', 'msf1']) {
    cases.push(sniffCase(`recognizes a HEIF file with major brand ${brand} as image/heif`, header(ftyp(brand, [brand]))));
  }
  cases.push(
    sniffCase('treats a file whose major brand is mif1 as an image', header(ftyp('mif1', ['mif1', 'miaf']))),
    sniffCase('checks compatible brands before MP4 brands', header(ftyp('mp42', ['mp42', 'mif1']))),
    sniffCase('recognizes QuickTime movies', header(ftyp('qt  ', ['qt  ']))),
  );
  for (const brand of ['isom', 'iso2', 'iso4', 'iso5', 'iso6', 'mp41', 'mp42', 'avc1', 'M4V ', 'dash', '3gp4', '3gp5', '3g2a']) {
    cases.push(sniffCase(`recognizes an MP4 file with major brand "${brand}"`, header(ftyp(brand, [brand]))));
  }
  cases.push(
    sniffCase(
      'recognizes an MP4 file with major brand isom and only MP4 compatible brands',
      header(ftyp('isom', ['isom', 'iso2', 'avc1', 'mp41'])),
    ),
    sniffCase('recognizes WebM', webm),
    sniffCase('refuses an ftyp box with an unknown brand', header(ftyp('abcd', ['abcd']))),
    sniffCase('refuses empty and truncated headers without throwing (empty)', new Uint8Array(0)),
    sniffCase('refuses empty and truncated headers without throwing (two JPEG bytes)', Uint8Array.from([0xff, 0xd8])),
    sniffCase('refuses empty and truncated headers without throwing (seven PNG bytes)', png.slice(0, PNG_TRUNCATED_HEADER_BYTES)),
    sniffCase('refuses empty and truncated headers without throwing (ftyp only)', asciiBytes('ftyp')),
  );
  for (const seed of RANDOM_HEADER_SEEDS) {
    cases.push(sniffCase(`refuses random bytes (seed ${seed})`, seededBytes(SNIFF_HEADER_BYTES, seed)));
  }
  const lateHeif = ftyp('isom', [...Array.from({ length: LATE_BRAND_PADDING }, () => 'isom'), 'mif1']);
  const shortFtyp = ftyp('isom', ['mif1']);
  shortFtyp.set(u32be(SHORT_FTYP_SIZE), 0);
  const zeroFtyp = ftyp('mp42', ['heic']);
  zeroFtyp.set(u32be(0), 0);
  cases.push(
    sniffCase('edge: only the first three JPEG bytes', Uint8Array.from([0xff, 0xd8, 0xff])),
    sniffCase('edge: a compatible HEIF brand past the 64-byte window is ignored', lateHeif),
    sniffCase('edge: the 64-byte window is applied to a longer input', concat(jpeg, new Uint8Array(SNIFF_HEADER_BYTES))),
    sniffCase('edge: brands beyond the declared ftyp size are ignored', shortFtyp),
    sniffCase('edge: an ftyp size of 0 reads only the major brand', zeroFtyp),
    sniffCase('edge: a 12-byte ftyp with an MP4 major brand', asciiBytes('\u0000\u0000\u0000\u000cftypisom')),
    sniffCase('edge: the major brand is matched case-sensitively', header(ftyp('ISOM', ['ISOM']))),
    sniffCase('edge: RIFF and WEBP without a chunk', concat(asciiBytes('RIFF'), u32le(MINIMAL_RIFF_SIZE), asciiBytes('WEBP'))),
    sniffCase('edge: RIFF cut before the WEBP tag', concat(asciiBytes('RIFF'), u32le(MINIMAL_RIFF_SIZE), asciiBytes('WEB'))),
  );
  return cases;
}

function fullTiff(endian: Endian = 'little'): Uint8Array {
  return buildTiff({ endian, dateTimeOriginal: DATE, offsetTimeOriginal: OFFSET, gps: PARIS, orientation: ORIENTATION_ROTATE_90 });
}

async function tiffCase(writer: GoldenWriter, name: string, tiff: Uint8Array): Promise<GoldenCase> {
  const fullName = `parseTiffExif: ${name}`;
  return functionCase(fullName, 'parseTiffExif', { tiff: await inputRef(writer, `tiff-${name}`, tiff) }, () => parseTiffExif(tiff));
}

async function imageCase(
  writer: GoldenWriter,
  name: string,
  container: 'jpeg' | 'png' | 'webp',
  bytes: Uint8Array,
): Promise<GoldenCase> {
  const source = await inputRef(writer, `${container}-${name}`, bytes);
  return asyncFunctionCase(`readImageMetadata: ${name}`, 'readImageMetadata', { source, container }, () =>
    readImageMetadata(recordingSource(contentOf(bytes)), container),
  );
}

async function exifCases(writer: GoldenWriter): Promise<GoldenCase[]> {
  const cases: GoldenCase[] = [];
  const tiffs: [string, TiffOptions | Uint8Array][] = [];
  for (const endian of ['little', 'big'] as const) {
    tiffs.push([`reads date, offset, GPS and orientation (${endian}-endian)`, fullTiff(endian)]);
    tiffs.push([`negates southern and western coordinates (${endian}-endian)`, { endian, gps: SANTIAGO }]);
  }
  tiffs.push(
    ['returns empty fields for a TIFF block without tags', {}],
    ['rejects the exact 0,0 point and keeps the other fields', { dateTimeOriginal: DATE, gps: { latitude: 0, longitude: 0 } }],
    ['rejects latitudes beyond 90 degrees (91)', { gps: { latitude: 91, longitude: 10 } }],
    ['rejects latitudes beyond 90 degrees (-91)', { gps: { latitude: -91, longitude: 10 } }],
    ['rejects longitudes beyond 180 degrees (181)', { gps: { latitude: 10, longitude: 181 } }],
    ['rejects longitudes beyond 180 degrees (-181)', { gps: { latitude: 10, longitude: -181 } }],
    ['turns a date that does not match YYYY:MM:DD HH:MM:SS into null (dashes)', { dateTimeOriginal: '2026-05-01 10:00:00' }],
    ['turns a date that does not match YYYY:MM:DD HH:MM:SS into null (blank)', { dateTimeOriginal: '    :  :     :  :  ' }],
    ['turns a date that does not match YYYY:MM:DD HH:MM:SS into null (word)', { dateTimeOriginal: 'yesterday' }],
    ['turns an offset that does not match the sign-hours-minutes format into null (word)', { offsetTimeOriginal: 'later' }],
    ['turns an offset that does not match the sign-hours-minutes format into null (+2)', { offsetTimeOriginal: '+2' }],
    ['keeps a valid negative offset', { offsetTimeOriginal: '-03:30' }],
  );
  const full = fullTiff();
  for (const length of [0, 4, 12, full.length - TRUNCATE_TAIL_BYTES]) {
    tiffs.push([`flags a truncated block as malformed without throwing (length ${length})`, full.slice(0, length)]);
  }
  const wrongOrder = fullTiff();
  wrongOrder[0] = WRONG_ORDER_BYTE;
  wrongOrder[1] = WRONG_ORDER_BYTE;
  const wrongMagic = fullTiff();
  wrongMagic[MAGIC_OFFSET] = WRONG_MAGIC;
  const oversized = buildTiff({ orientation: ORIENTATION_ROTATE_90 });
  oversized[IFD0_COUNT_OFFSET] = OVERSIZED_IFD_COUNT & BYTE_MASK;
  oversized[IFD0_COUNT_OFFSET + 1] = OVERSIZED_IFD_COUNT >> BITS_PER_BYTE;
  tiffs.push(
    ['flags a wrong byte order marker or magic number as malformed (byte order)', wrongOrder],
    ['flags a wrong byte order marker or magic number as malformed (magic)', wrongMagic],
    ['flags an IFD that claims more entries than the data holds as malformed', oversized],
    ['terminates when the sub-IFD pointers point back at IFD0', { orientation: ORIENTATION_ROTATE_90, selfReferencingPointers: true }],
    ['returns null for an ASCII value of 200,000 bytes without throwing', { orientation: ORIENTATION_ROTATE_90, dateTimeOriginal: 'x'.repeat(HUGE_ASCII_CHARS) }],
    ['edge: orientation 9 is out of range', { orientation: 9 }],
    ['edge: orientation 0 is out of range', { orientation: 0 }],
    ['edge: orientation 1 big-endian', { endian: 'big', orientation: 1 }],
    ['edge: a date with an impossible month still matches the pattern', { dateTimeOriginal: '2026:13:45 99:99:99' }],
    ['edge: an ASCII value of exactly 64 bytes is read and trailing NULs are removed', { dateTimeOriginal: DATE.padEnd(EXIF_ASCII_MAX_BYTES - 1, '\u0000') }],
    ['edge: an ASCII value of 65 bytes is ignored', { dateTimeOriginal: DATE.padEnd(EXIF_ASCII_MAX_BYTES, '\u0000') }],
    ['edge: a NUL inside the date is kept and fails the pattern', { dateTimeOriginal: `${DATE}\u0000x` }],
    ['edge: an offset with minutes 99 matches the pattern', { offsetTimeOriginal: '+05:99' }],
  );
  for (const [name, input] of tiffs) {
    cases.push(await tiffCase(writer, name, input instanceof Uint8Array ? input : buildTiff(input)));
  }
  cases.push(functionCase('constant: exports the ASCII limit', 'EXIF_ASCII_MAX_BYTES', {}, () => EXIF_ASCII_MAX_BYTES));
  for (const container of ['jpeg', 'png', 'webp'] as const) {
    cases.push(
      await asyncFunctionCase(
        `readImageMetadata: returns malformed instead of throwing when the source fails (${container})`,
        'readImageMetadata',
        { source: failingSourceJson(FAILING_SIZE, FAILING_MESSAGE), container },
        () => readImageMetadata(failingSource(FAILING_SIZE, FAILING_MESSAGE), container),
      ),
    );
  }
  const exifJpeg = buildJpeg({ segments: [jfifApp0(), exifApp1(fullTiff())] });
  const images: [string, 'jpeg' | 'png' | 'webp', Uint8Array][] = [
    ['finds the Exif APP1 after an APP0', 'jpeg', buildJpeg({ segments: [jfifApp0(), exifApp1(fullTiff())], scanBytes: JPEG_SCAN_BYTES })],
    ['skips an XMP APP1 that comes before the Exif APP1', 'jpeg', buildJpeg({ segments: [xmpApp1(), exifApp1(fullTiff('big'))] })],
    ['returns empty fields for a JPEG without EXIF', 'jpeg', buildJpeg({ segments: [jfifApp0()], scanBytes: JPEG_SHORT_SCAN_BYTES })],
    [
      'stops at the start of scan and ignores an APP1 after it',
      'jpeg',
      buildJpeg({ segments: [jfifApp0()], scanBytes: JPEG_TINY_SCAN_BYTES, afterScan: [exifApp1(fullTiff())] }),
    ],
    ['flags a JPEG cut inside the Exif segment as malformed', 'jpeg', exifJpeg.slice(0, JPEG_CUT_BYTES)],
    ['flags bytes that are not a JPEG as malformed without throwing', 'jpeg', seededBytes(NOT_JPEG_BYTES, NOT_JPEG_SEED)],
    ['reads the eXIf chunk', 'png', buildPng({ exifTiff: fullTiff() })],
    ['returns empty fields for a PNG without eXIf', 'png', buildPng()],
    ['flags a PNG cut inside the eXIf chunk as malformed', 'png', buildPng({ exifTiff: fullTiff() }).slice(0, PNG_CUT_BYTES)],
    ['reads the EXIF chunk', 'webp', buildWebp({ exifTiff: fullTiff() })],
    ['skips a leading Exif identifier inside the EXIF chunk', 'webp', buildWebp({ exifTiff: fullTiff('big'), exifPrefix: true })],
    ['returns empty fields for a WebP without EXIF', 'webp', buildWebp()],
    ['edge: an empty JPEG file is malformed', 'jpeg', new Uint8Array(0)],
    ['edge: a JPEG with a restart marker before the Exif APP1', 'jpeg', buildJpeg({ segments: [Uint8Array.from([BYTE_MASK, 0xd0]), exifApp1(fullTiff())] })],
    ['edge: a JPEG with an Exif APP1 whose TIFF is broken', 'jpeg', buildJpeg({ segments: [exifApp1(asciiBytes('XX*\u0000'))] })],
    ['edge: a JPEG segment length below 2 is malformed', 'jpeg', Uint8Array.from([BYTE_MASK, 0xd8, BYTE_MASK, 0xe0, 0, 1])],
    ['edge: a PNG with the wrong signature is malformed', 'png', concat(asciiBytes('PNG'), new Uint8Array(JPEG_TINY_SCAN_BYTES))],
    ['edge: a WebP with the wrong form type is malformed', 'webp', concat(asciiBytes('RIFF'), u32le(MINIMAL_RIFF_SIZE), asciiBytes('AVI '))],
    ['edge: a JPEG file passed as PNG is malformed', 'png', exifJpeg],
  ];
  for (const [name, container, bytes] of images) cases.push(await imageCase(writer, name, container, bytes));
  const scanJpeg = buildJpeg({
    segments: [...Array.from({ length: SCAN_FILLER_COUNT }, () => fillerApp2(SCAN_FILLER_BYTES)), exifApp1(fullTiff())],
  });
  const recording = recordingSource(contentOf(scanJpeg));
  const scanName = 'never reads beyond the scan limit and misses an Exif segment past it';
  const scanResult = await asyncFunctionCase(
    `readImageMetadata: ${scanName}`,
    'readImageMetadata',
    { source: await inputRef(writer, `jpeg-${scanName}`, scanJpeg), container: 'jpeg' },
    () => readImageMetadata(recording, 'jpeg'),
  );
  cases.push({ ...scanResult, readStats: recording.stats() });
  return cases;
}

const CAPTURE_BASE: CaptureTimeInput = {
  exifDateTimeOriginal: null,
  exifOffset: null,
  containerCreationUtc: null,
  fileModifiedUtc: null,
  nowUtc: NOW,
  deviceOffsetMinutes: 60,
};

function captureCase(name: string, change: Partial<CaptureTimeInput>): GoldenCase {
  const input: CaptureTimeInput = { ...CAPTURE_BASE, ...change };
  return functionCase(`resolveCaptureTime: ${name}`, 'resolveCaptureTime', { input }, () => resolveCaptureTime(input));
}

function earliestCase(name: string, times: readonly ResolvedTime[]): GoldenCase {
  return functionCase(`earliestTime: ${name}`, 'earliestTime', { times }, () => earliestTime(times));
}

function captureTimeCases(): GoldenCase[] {
  const exif = '2026:05:01 10:00:00';
  const cases: GoldenCase[] = [
    captureCase('prefers EXIF with its own offset over every other source', {
      exifDateTimeOriginal: exif,
      exifOffset: '+02:00',
      containerCreationUtc: '2026-04-01T00:00:00.000Z',
      fileModifiedUtc: '2026-03-01T00:00:00.000Z',
    }),
    captureCase('reads EXIF without an offset as local time at the device offset', { exifDateTimeOriginal: exif, deviceOffsetMinutes: -300 }),
    captureCase('handles a negative EXIF offset with a half hour', { exifDateTimeOriginal: exif, exifOffset: '-03:30' }),
    captureCase('rolls the date over when the offset moves the time across midnight', {
      exifDateTimeOriginal: '2026:12:31 23:30:00',
      exifOffset: '-02:00',
    }),
    captureCase('falls back to the device offset when the EXIF offset is not valid', {
      exifDateTimeOriginal: exif,
      exifOffset: 'bogus',
      deviceOffsetMinutes: 120,
    }),
    captureCase('uses the container creation time (UTC, device offset) when EXIF is not valid', {
      exifDateTimeOriginal: 'garbage',
      containerCreationUtc: '2026-05-01T08:00:00.000Z',
      fileModifiedUtc: '2026-03-01T00:00:00.000Z',
    }),
    captureCase('treats the zero EXIF date as missing', {
      exifDateTimeOriginal: '0000:00:00 00:00:00',
      fileModifiedUtc: '2026-03-01T00:00:00.000Z',
    }),
    captureCase('uses the file modified time when nothing is embedded', { fileModifiedUtc: '2026-03-01T00:00:00.000Z' }),
    captureCase('uses now when no other source exists', {}),
  ];
  for (const exifOffset of ['+14:00', '-14:00']) {
    cases.push(captureCase(`accepts the EXIF offset ${exifOffset} at the limit`, { exifDateTimeOriginal: exif, exifOffset }));
  }
  for (const exifOffset of ['+14:01', '-14:01', '+99:00', '-23:59']) {
    cases.push(
      captureCase(`uses the device offset for the out-of-range EXIF offset ${exifOffset}`, {
        exifDateTimeOriginal: exif,
        exifOffset,
        deviceOffsetMinutes: 120,
      }),
    );
  }
  cases.push(
    captureCase('edge: an EXIF offset with minutes 60 uses the device offset', { exifDateTimeOriginal: exif, exifOffset: '+05:60' }),
    captureCase('edge: an EXIF offset +00:00 is used', { exifDateTimeOriginal: exif, exifOffset: '+00:00', deviceOffsetMinutes: 120 }),
    captureCase('edge: an EXIF offset -00:00 is 0', { exifDateTimeOriginal: exif, exifOffset: '-00:00', deviceOffsetMinutes: 120 }),
    captureCase('edge: February 30 in EXIF is not a date', { exifDateTimeOriginal: '2026:02:30 10:00:00', fileModifiedUtc: '2026-03-01T00:00:00.000Z' }),
    captureCase('edge: February 29 in a leap year', { exifDateTimeOriginal: '2028:02:29 23:59:59', exifOffset: '+00:00' }),
    captureCase('edge: hour 24 in EXIF is not a time', { exifDateTimeOriginal: '2026:05:01 24:00:00' }),
    captureCase('edge: second 60 in EXIF is not a time', { exifDateTimeOriginal: '2026:05:01 23:59:60' }),
    captureCase('edge: EXIF years 0000 to 0099 are refused (Date.UTC maps them to 19xx)', { exifDateTimeOriginal: '0050:06:15 12:00:00' }),
    captureCase('edge: EXIF year 0100 is accepted', { exifDateTimeOriginal: '0100:06:15 12:00:00', exifOffset: '+00:00' }),
    captureCase('edge: EXIF year 9999 crossing into 10000', { exifDateTimeOriginal: '9999:12:31 23:30:00', exifOffset: '-01:00' }),
    captureCase('edge: EXIF with surrounding spaces is refused', { exifDateTimeOriginal: ' 2026:05:01 10:00:00' }),
    captureCase('edge: an empty EXIF offset uses the device offset', { exifDateTimeOriginal: exif, exifOffset: '' }),
    captureCase('edge: the container time is normalized to milliseconds', { containerCreationUtc: '2026-05-01T08:00:00Z' }),
    captureCase('edge: a container time with an offset is converted to UTC', { containerCreationUtc: '2026-05-01T10:00:00+02:00' }),
    captureCase('edge: a date-only container time is UTC midnight', { containerCreationUtc: '2026-05-01' }),
    captureCase('edge: an unparsable container time falls back to the file time', {
      containerCreationUtc: 'garbage',
      fileModifiedUtc: '2026-03-01T00:00:00.000Z',
    }),
    captureCase('edge: an unparsable file time falls back to now', { fileModifiedUtc: 'garbage' }),
    captureCase('edge: nowUtc is returned verbatim', { nowUtc: '2026-11-12T10:00:00Z', deviceOffsetMinutes: -720 }),
    earliestCase('picks the time with the smallest capturedAtUtc', [
      { capturedAtUtc: '2026-05-02T08:00:00.000Z', utcOffsetMinutes: 0, source: 'exif' },
      { capturedAtUtc: '2026-05-01T08:00:00.000Z', utcOffsetMinutes: 120, source: 'container' },
      { capturedAtUtc: '2026-05-03T08:00:00.000Z', utcOffsetMinutes: 60, source: 'now' },
    ]),
    earliestCase('returns the only time of a single-item list', [{ capturedAtUtc: NOW, utcOffsetMinutes: 60, source: 'now' }]),
    earliestCase('edge: an empty list throws', []),
    earliestCase('edge: the first of two equal times wins', [
      { capturedAtUtc: '2026-05-01T08:00:00.000Z', utcOffsetMinutes: 0, source: 'file' },
      { capturedAtUtc: '2026-05-01T08:00:00.000Z', utcOffsetMinutes: 60, source: 'exif' },
    ]),
    earliestCase('edge: times are compared as instants, not strings', [
      { capturedAtUtc: '2026-05-01T08:00:00.000Z', utcOffsetMinutes: 0, source: 'file' },
      { capturedAtUtc: '2026-05-01T07:59:59.999Z', utcOffsetMinutes: 0, source: 'container' },
    ]),
  );
  for (const timezoneOffset of [-120, 300, 0]) {
    cases.push(
      functionCase(
        `deviceOffsetMinutes: is the negated timezone offset of the date (${timezoneOffset})`,
        'deviceOffsetMinutes',
        { timezoneOffsetMinutes: timezoneOffset },
        () => deviceOffsetMinutes({ getTimezoneOffset: () => timezoneOffset } as Date),
      ),
    );
  }
  return cases;
}

function geometryCases(): GoldenCase[] {
  const cases: GoldenCase[] = [];
  const rows: { readonly name: string; readonly width: number; readonly height: number; readonly maxEdge: number }[] = [
    ...[
      { width: 4000, height: 3000 },
      { width: 3000, height: 4000 },
      { width: 4032, height: 3024 },
      { width: 1000, height: 333 },
      { width: 512, height: 512 },
      { width: 300, height: 200 },
      { width: 1, height: 1 },
      { width: 1, height: 10_000 },
      { width: 10_000, height: 1 },
    ].map((row) => ({ ...row, name: `fits ${row.width} x ${row.height} within the maximum edge`, maxEdge: MAX_EDGE })),
    { name: 'never upscales', width: 100, height: 50, maxEdge: 1024 },
    { name: 'edge: a .5 product rounds up', width: 1000, height: 3, maxEdge: 500 },
    { name: 'edge: 2.5 rounds up to 3', width: 2000, height: 5, maxEdge: 1000 },
    { name: 'edge: a product just under .5 rounds down', width: 1025, height: 3, maxEdge: MAX_EDGE },
    { name: 'edge: one pixel over the edge', width: 513, height: 512, maxEdge: MAX_EDGE },
  ];
  for (const { name, width, height, maxEdge } of rows) {
    cases.push(functionCase(`fitWithin: ${name}`, 'fitWithin', { width, height, maxEdge }, () => fitWithin(width, height, maxEdge)));
  }
  const display = (name: string, width: number, height: number, orientation: number | null) =>
    functionCase(`displaySize: ${name}`, 'displaySize', { width, height, orientation }, () => displaySize(width, height, orientation));
  for (const orientation of [1, 2, 3, 4]) cases.push(display(`keeps the size for orientation ${orientation}`, LANDSCAPE.width, LANDSCAPE.height, orientation));
  for (const orientation of [5, 6, 7, 8]) cases.push(display(`swaps width and height for orientation ${orientation}`, LANDSCAPE.width, LANDSCAPE.height, orientation));
  cases.push(
    display('keeps the size when there is no orientation', LANDSCAPE.width, LANDSCAPE.height, null),
    display('edge: orientation 0 keeps the size', LANDSCAPE.width, LANDSCAPE.height, 0),
    display('edge: orientation 9 keeps the size', LANDSCAPE.width, LANDSCAPE.height, ORIENTATION_OUT_OF_RANGE),
  );
  return cases;
}

export async function writeMediaImageGoldens(writer: GoldenWriter): Promise<void> {
  await writer.functionCases(PATHS.sniff, sniffCases());
  await writer.functionCases(PATHS.exif, await exifCases(writer));
  await writer.functionCases(PATHS.captureTime, captureTimeCases());
  await writer.functionCases(PATHS.geometry, geometryCases());
}

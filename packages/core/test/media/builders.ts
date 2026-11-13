import type { ByteSource } from '../../src/media/byte-source.ts';

export type Endian = 'little' | 'big';
export type ByteInput = Uint8Array | readonly number[];

const BYTE_MASK = 0xff;
const TIFF_MAGIC = 42;
const TIFF_HEADER_BYTES = 8;
const IFD_COUNT_BYTES = 2;
const IFD_ENTRY_BYTES = 12;
const IFD_NEXT_BYTES = 4;
const INLINE_VALUE_BYTES = 4;
const RATIONAL_SECONDS_DENOMINATOR = 10_000;
const MINUTES_PER_DEGREE = 60;
const TYPE_ASCII = 2;
const TYPE_SHORT = 3;
const TYPE_LONG = 4;
const TYPE_RATIONAL = 5;
const SOS_SEGMENT = [0xff, 0xda, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f, 0x00];
const JPEG_SOI = [0xff, 0xd8];
const JPEG_EOI = [0xff, 0xd9];
const JPEG_MARKER_APP0 = 0xe0;
const JPEG_MARKER_APP1 = 0xe1;
const JPEG_MARKER_APP2 = 0xe2;
const JPEG_SEGMENT_LENGTH_BYTES = 2;
const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const PNG_CRC_PLACEHOLDER = [0, 0, 0, 0];
const SCAN_PATTERN_MODULO = 251;
const SCAN_PATTERN_STEP = 7;
const MAX_VIRTUAL_READ_BYTES = 16_777_216;
const EXIF_IDENTIFIER = 'Exif\0\0';
const XMP_IDENTIFIER = 'http://ns.adobe.com/xap/1.0/\0';
const BOX_HEADER_BYTES = 8;
const FILLER_SEED_SHIFT_LEFT = 13;
const FILLER_SEED_SHIFT_RIGHT = 17;
const FILLER_SEED_SHIFT_LEFT_SECOND = 5;
const MVHD_V0_PAYLOAD_BYTES = 100;
const MVHD_V1_PAYLOAD_BYTES = 112;
const QUICKTIME_LANGUAGE_CODE = 0x15c7;
const HDLR_RESERVED_BYTES = 12;

export const TIFF_TAGS = Object.freeze({
  orientation: 0x0112,
  exifPointer: 0x8769,
  gpsPointer: 0x8825,
  dateTimeOriginal: 0x9003,
  offsetTimeOriginal: 0x9011,
  gpsLatitudeRef: 1,
  gpsLatitude: 2,
  gpsLongitudeRef: 3,
  gpsLongitude: 4,
} as const);

export function asciiBytes(text: string): Uint8Array {
  return Uint8Array.from(text, (character) => character.charCodeAt(0) & BYTE_MASK);
}

export function concat(...parts: readonly ByteInput[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

function u16(value: number, endian: Endian): number[] {
  const bytes = [(value >>> 8) & BYTE_MASK, value & BYTE_MASK];
  return endian === 'big' ? bytes : bytes.reverse();
}

function u32(value: number, endian: Endian): number[] {
  const bytes = [
    (value >>> 24) & BYTE_MASK,
    (value >>> 16) & BYTE_MASK,
    (value >>> 8) & BYTE_MASK,
    value & BYTE_MASK,
  ];
  return endian === 'big' ? bytes : bytes.reverse();
}

export function u16be(value: number): number[] {
  return u16(value, 'big');
}

export function u32be(value: number): number[] {
  return u32(value, 'big');
}

export function u32le(value: number): number[] {
  return u32(value, 'little');
}

export function u64be(value: bigint): number[] {
  const bytes: number[] = [];
  for (let shift = 56n; shift >= 0n; shift -= 8n) bytes.push(Number((value >> shift) & 0xffn));
  return bytes;
}

export function seededBytes(length: number, seed: number): Uint8Array {
  const bytes = new Uint8Array(length);
  let state = seed | 0 || 1;
  for (let index = 0; index < length; index += 1) {
    state ^= state << FILLER_SEED_SHIFT_LEFT;
    state ^= state >>> FILLER_SEED_SHIFT_RIGHT;
    state ^= state << FILLER_SEED_SHIFT_LEFT_SECOND;
    bytes[index] = state & BYTE_MASK;
  }
  return bytes;
}

export type GpsInput = { readonly latitude: number; readonly longitude: number };

export type TiffOptions = {
  readonly endian?: Endian;
  readonly dateTimeOriginal?: string;
  readonly offsetTimeOriginal?: string;
  readonly gps?: GpsInput;
  readonly orientation?: number;
  readonly selfReferencingPointers?: boolean;
};

type IfdEntry = {
  readonly tag: number;
  readonly type: number;
  readonly count: number;
  readonly data: readonly number[];
  readonly pointer?: 'exif' | 'gps';
};

type ExternalData = { next: number; readonly bytes: number[] };

function asciiEntry(tag: number, text: string): IfdEntry {
  const data = Array.from(asciiBytes(text));
  data.push(0);
  return { tag, type: TYPE_ASCII, count: data.length, data };
}

function shortEntry(tag: number, value: number, endian: Endian): IfdEntry {
  return { tag, type: TYPE_SHORT, count: 1, data: [...u16(value, endian), 0, 0] };
}

function rationalEntry(
  tag: number,
  parts: readonly (readonly [number, number])[],
  endian: Endian,
): IfdEntry {
  const data = parts.flatMap(([numerator, denominator]) => [
    ...u32(numerator, endian),
    ...u32(denominator, endian),
  ]);
  return { tag, type: TYPE_RATIONAL, count: parts.length, data };
}

function degreesMinutesSeconds(value: number): readonly (readonly [number, number])[] {
  const absolute = Math.abs(value);
  const degrees = Math.floor(absolute);
  const minutesWhole = (absolute - degrees) * MINUTES_PER_DEGREE;
  const minutes = Math.floor(minutesWhole);
  const seconds = Math.round(
    (minutesWhole - minutes) * MINUTES_PER_DEGREE * RATIONAL_SECONDS_DENOMINATOR,
  );
  return [
    [degrees, 1],
    [minutes, 1],
    [seconds, RATIONAL_SECONDS_DENOMINATOR],
  ];
}

function gpsEntries(gps: GpsInput, endian: Endian): IfdEntry[] {
  return [
    asciiEntry(TIFF_TAGS.gpsLatitudeRef, gps.latitude < 0 ? 'S' : 'N'),
    rationalEntry(TIFF_TAGS.gpsLatitude, degreesMinutesSeconds(gps.latitude), endian),
    asciiEntry(TIFF_TAGS.gpsLongitudeRef, gps.longitude < 0 ? 'W' : 'E'),
    rationalEntry(TIFF_TAGS.gpsLongitude, degreesMinutesSeconds(gps.longitude), endian),
  ];
}

function ifdByteLength(entries: readonly IfdEntry[], force = false): number {
  return entries.length === 0 && !force
    ? 0
    : IFD_COUNT_BYTES + IFD_ENTRY_BYTES * entries.length + IFD_NEXT_BYTES;
}

function appendAll(target: number[], source: readonly number[]): void {
  for (let index = 0; index < source.length; index += 1) target.push(source[index] ?? 0);
}

function encodeIfd(
  entries: readonly IfdEntry[],
  endian: Endian,
  external: ExternalData,
  pointers: Readonly<Record<'exif' | 'gps', number>>,
  force = false,
): number[] {
  if (entries.length === 0 && !force) return [];
  const bytes = [...u16(entries.length, endian)];
  for (const entry of entries) {
    bytes.push(...u16(entry.tag, endian), ...u16(entry.type, endian), ...u32(entry.count, endian));
    if (entry.pointer !== undefined) {
      bytes.push(...u32(pointers[entry.pointer], endian));
    } else if (entry.data.length <= INLINE_VALUE_BYTES) {
      bytes.push(...entry.data, ...new Array<number>(INLINE_VALUE_BYTES - entry.data.length).fill(0));
    } else {
      const paddedLength = entry.data.length + (entry.data.length % 2);
      bytes.push(...u32(external.next, endian));
      appendAll(external.bytes, entry.data);
      if (paddedLength > entry.data.length) external.bytes.push(0);
      external.next += paddedLength;
    }
  }
  bytes.push(...u32(0, endian));
  return bytes;
}

export function buildTiff(options: TiffOptions = {}): Uint8Array {
  const endian = options.endian ?? 'little';
  const ifd0: IfdEntry[] = [];
  const exif: IfdEntry[] = [];
  const gps: IfdEntry[] = [];
  if (options.orientation !== undefined) {
    ifd0.push(shortEntry(TIFF_TAGS.orientation, options.orientation, endian));
  }
  if (options.dateTimeOriginal !== undefined) {
    exif.push(asciiEntry(TIFF_TAGS.dateTimeOriginal, options.dateTimeOriginal));
  }
  if (options.offsetTimeOriginal !== undefined) {
    exif.push(asciiEntry(TIFF_TAGS.offsetTimeOriginal, options.offsetTimeOriginal));
  }
  if (options.gps !== undefined) gps.push(...gpsEntries(options.gps, endian));
  const selfReferencing = options.selfReferencingPointers === true;
  if (exif.length > 0 || selfReferencing) {
    ifd0.push({ tag: TIFF_TAGS.exifPointer, type: TYPE_LONG, count: 1, data: [], pointer: 'exif' });
  }
  if (gps.length > 0 || selfReferencing) {
    ifd0.push({ tag: TIFF_TAGS.gpsPointer, type: TYPE_LONG, count: 1, data: [], pointer: 'gps' });
  }
  ifd0.sort((left, right) => left.tag - right.tag);
  const exifOffset = TIFF_HEADER_BYTES + ifdByteLength(ifd0, true);
  const gpsOffset = exifOffset + ifdByteLength(exif);
  const external: ExternalData = { next: gpsOffset + ifdByteLength(gps), bytes: [] };
  const pointers = selfReferencing
    ? { exif: TIFF_HEADER_BYTES, gps: TIFF_HEADER_BYTES }
    : { exif: exifOffset, gps: gpsOffset };
  const encodedIfd0 = encodeIfd(ifd0, endian, external, pointers, true);
  const encodedExif = encodeIfd(exif, endian, external, pointers);
  const encodedGps = encodeIfd(gps, endian, external, pointers);
  const order = endian === 'little' ? [0x49, 0x49] : [0x4d, 0x4d];
  return concat(
    order,
    u16(TIFF_MAGIC, endian),
    u32(TIFF_HEADER_BYTES, endian),
    encodedIfd0,
    encodedExif,
    encodedGps,
    external.bytes,
  );
}

export function jpegSegment(marker: number, payload: ByteInput): Uint8Array {
  return concat(
    [BYTE_MASK, marker],
    u16be(payload.length + JPEG_SEGMENT_LENGTH_BYTES),
    payload,
  );
}

export function jfifApp0(): Uint8Array {
  return jpegSegment(JPEG_MARKER_APP0, [
    ...asciiBytes('JFIF\0'),
    1,
    1,
    0,
    0,
    1,
    0,
    1,
    0,
    0,
  ]);
}

export function exifApp1(tiff: Uint8Array): Uint8Array {
  return jpegSegment(JPEG_MARKER_APP1, concat(asciiBytes(EXIF_IDENTIFIER), tiff));
}

export function xmpApp1(): Uint8Array {
  return jpegSegment(JPEG_MARKER_APP1, asciiBytes(`${XMP_IDENTIFIER}<x:xmpmeta/>`));
}

export function fillerApp2(payloadBytes: number): Uint8Array {
  return jpegSegment(JPEG_MARKER_APP2, new Uint8Array(payloadBytes));
}

export type JpegOptions = {
  readonly segments?: readonly Uint8Array[];
  readonly scanBytes?: number;
  readonly afterScan?: readonly Uint8Array[];
};

export function buildJpeg(options: JpegOptions = {}): Uint8Array {
  const scan = Uint8Array.from({ length: options.scanBytes ?? 0 }, (_, index) =>
    (index * SCAN_PATTERN_STEP) % SCAN_PATTERN_MODULO,
  );
  return concat(
    JPEG_SOI,
    ...(options.segments ?? []),
    SOS_SEGMENT,
    scan,
    ...(options.afterScan ?? []),
    JPEG_EOI,
  );
}

export function insertExifIntoJpeg(jpeg: Uint8Array, tiff: Uint8Array): Uint8Array {
  const hasApp0 = jpeg[2] === BYTE_MASK && jpeg[3] === JPEG_MARKER_APP0;
  const insertAt = hasApp0
    ? JPEG_SOI.length +
      JPEG_SEGMENT_LENGTH_BYTES +
      (((jpeg[4] ?? 0) << 8) | (jpeg[5] ?? 0))
    : JPEG_SOI.length;
  return concat(jpeg.subarray(0, insertAt), exifApp1(tiff), jpeg.subarray(insertAt));
}

export function pngChunk(type: string, data: ByteInput): Uint8Array {
  return concat(u32be(data.length), asciiBytes(type), data, PNG_CRC_PLACEHOLDER);
}

export type PngOptions = { readonly exifTiff?: Uint8Array; readonly idatBytes?: number };

export function buildPng(options: PngOptions = {}): Uint8Array {
  const header = [...u32be(1), ...u32be(1), 8, 2, 0, 0, 0];
  return concat(
    PNG_SIGNATURE,
    pngChunk('IHDR', header),
    ...(options.exifTiff === undefined ? [] : [pngChunk('eXIf', options.exifTiff)]),
    pngChunk('IDAT', new Uint8Array(options.idatBytes ?? 16)),
    pngChunk('IEND', []),
  );
}

export function riffChunk(fourcc: string, data: ByteInput): Uint8Array {
  const padding = data.length % 2 === 0 ? [] : [0];
  return concat(asciiBytes(fourcc), u32le(data.length), data, padding);
}

export type WebpOptions = { readonly exifTiff?: Uint8Array; readonly exifPrefix?: boolean };

export function buildWebp(options: WebpOptions = {}): Uint8Array {
  const exifData =
    options.exifTiff === undefined
      ? null
      : concat(options.exifPrefix === true ? asciiBytes(EXIF_IDENTIFIER) : [], options.exifTiff);
  const chunks = concat(
    riffChunk('VP8X', [exifData === null ? 0 : 0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
    ...(exifData === null ? [] : [riffChunk('EXIF', exifData)]),
    riffChunk('VP8 ', new Uint8Array(10)),
  );
  return concat(asciiBytes('RIFF'), u32le(chunks.length + 4), asciiBytes('WEBP'), chunks);
}

export function box(type: string | Uint8Array, payload: ByteInput): Uint8Array {
  const typeBytes = typeof type === 'string' ? asciiBytes(type) : type;
  return concat(u32be(payload.length + BOX_HEADER_BYTES), typeBytes, payload);
}

export function largeBox(type: string, payload: ByteInput): Uint8Array {
  return concat(u32be(1), asciiBytes(type), u64be(BigInt(payload.length + BOX_HEADER_BYTES + 8)), payload);
}

export function ftyp(major: string, compatible: readonly string[] = []): Uint8Array {
  return box('ftyp', concat(asciiBytes(major), u32be(0), ...compatible.map(asciiBytes)));
}

export type MvhdOptions = { readonly version: 0 | 1; readonly creationSeconds1904: number };

export function mvhd(options: MvhdOptions): Uint8Array {
  const body =
    options.version === 0
      ? concat(
          [0, 0, 0, 0],
          u32be(options.creationSeconds1904),
          new Uint8Array(MVHD_V0_PAYLOAD_BYTES - 8),
        )
      : concat(
          [1, 0, 0, 0],
          u64be(BigInt(options.creationSeconds1904)),
          new Uint8Array(MVHD_V1_PAYLOAD_BYTES - 12),
        );
  return box('mvhd', body);
}

export const XYZ_TYPE = Uint8Array.of(0xa9, 0x78, 0x79, 0x7a);

export function xyzBox(text: string): Uint8Array {
  return box(XYZ_TYPE, concat(u16be(text.length), u16be(QUICKTIME_LANGUAGE_CODE), asciiBytes(text)));
}

export function udta(...children: readonly Uint8Array[]): Uint8Array {
  return box('udta', concat(...children));
}

export function hdlr(handlerType: string): Uint8Array {
  return box(
    'hdlr',
    concat([0, 0, 0, 0], u32be(0), asciiBytes(handlerType), new Uint8Array(HDLR_RESERVED_BYTES), [0]),
  );
}

export type Rotation = 0 | 90 | 180 | 270;

const FIXED_ONE = 0x10000;
const FIXED_MINUS_ONE = 0x1_0000_0000 - FIXED_ONE;
const MATRIX_W = 0x4000_0000;
const TKHD_V0_PREFIX_BYTES = 36;
const TKHD_V1_PREFIX_BYTES = 48;

const ROTATION_CELLS: Readonly<Record<Rotation, readonly [number, number, number, number]>> = {
  0: [FIXED_ONE, 0, 0, FIXED_ONE],
  90: [0, FIXED_ONE, FIXED_MINUS_ONE, 0],
  180: [FIXED_MINUS_ONE, 0, 0, FIXED_MINUS_ONE],
  270: [0, FIXED_MINUS_ONE, FIXED_ONE, 0],
};

export function rotationMatrix(rotation: Rotation): readonly number[] {
  const [a, b, c, d] = ROTATION_CELLS[rotation];
  return [a, b, 0, c, d, 0, 0, 0, MATRIX_W];
}

export type TkhdOptions = { readonly version: 0 | 1; readonly matrix: readonly number[] };

export function tkhd(options: TkhdOptions): Uint8Array {
  const prefix = options.version === 0 ? TKHD_V0_PREFIX_BYTES : TKHD_V1_PREFIX_BYTES;
  return box(
    'tkhd',
    concat(
      [options.version, 0, 0, 3],
      new Uint8Array(prefix),
      ...options.matrix.map((cell) => u32be(cell)),
      u32be(0),
      u32be(0),
    ),
  );
}

export function trak(handlerType: string, header: Uint8Array): Uint8Array {
  return box('trak', concat(header, box('mdia', concat(box('mdhd', new Uint8Array(24)), hdlr(handlerType)))));
}

export type AppleMetaEntry = { readonly key: string; readonly value: string };

export function appleMeta(entries: readonly AppleMetaEntry[], fullBox: boolean): Uint8Array {
  const hdlr = box(
    'hdlr',
    concat([0, 0, 0, 0], u32be(0), asciiBytes('mdta'), new Uint8Array(HDLR_RESERVED_BYTES), [0]),
  );
  const keyEntries = entries.map((entry) =>
    concat(u32be(entry.key.length + BOX_HEADER_BYTES), asciiBytes('mdta'), asciiBytes(entry.key)),
  );
  const keys = box('keys', concat([0, 0, 0, 0], u32be(entries.length), ...keyEntries));
  const items = entries.map((entry, position) =>
    box(
      Uint8Array.from(u32be(position + 1)),
      box('data', concat(u32be(1), u32be(0), asciiBytes(entry.value))),
    ),
  );
  const ilst = box('ilst', concat(...items));
  return box('meta', concat(fullBox ? [0, 0, 0, 0] : [], hdlr, keys, ilst));
}

export function moov(...children: readonly Uint8Array[]): Uint8Array {
  return box('moov', concat(...children));
}

export function mp4Seconds(isoUtc: string, epochOffsetSeconds: number): number {
  return Date.parse(isoUtc) / 1000 + epochOffsetSeconds;
}

export type ReadRecord = { readonly offset: number; readonly length: number };
export type InstrumentedSource = ByteSource & {
  readonly reads: readonly ReadRecord[];
  readonly bytesRequested: () => number;
  readonly furthestOffset: () => number;
};

export type VirtualSegment = { readonly offset: number; readonly bytes: Uint8Array };

export function virtualSource(size: number, segments: readonly VirtualSegment[]): InstrumentedSource {
  const reads: ReadRecord[] = [];
  return {
    size,
    reads,
    bytesRequested: () => reads.reduce((sum, record) => sum + record.length, 0),
    furthestOffset: () => reads.reduce((max, record) => Math.max(max, record.offset + record.length), 0),
    read: async (offset, length) => {
      reads.push({ offset, length });
      if (length > MAX_VIRTUAL_READ_BYTES) throw new Error(`Unexpected read of ${length} bytes`);
      const end = Math.min(size, offset + length);
      if (offset >= end) return new Uint8Array(0);
      const result = new Uint8Array(end - offset);
      for (const segment of segments) {
        const from = Math.max(offset, segment.offset);
        const to = Math.min(end, segment.offset + segment.bytes.length);
        if (from < to) {
          result.set(segment.bytes.subarray(from - segment.offset, to - segment.offset), from - offset);
        }
      }
      return result;
    },
  };
}

export function recordingSource(bytes: Uint8Array): InstrumentedSource {
  return virtualSource(bytes.length, [{ offset: 0, bytes }]);
}

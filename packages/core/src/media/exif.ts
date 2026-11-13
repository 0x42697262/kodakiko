import {
  readAscii,
  readUint16,
  readUint32,
  readUint8,
  sliceRange,
  startsWithAscii,
  startsWithBytes,
} from './binary.ts';
import type { ByteSource } from './byte-source.ts';
import {
  EXIF_ASCII_MAX_BYTES,
  EXIF_DATE_PATTERN,
  EXIF_IDENTIFIER,
  EXIF_MAX_IFD_ENTRIES,
  EXIF_OFFSET_PATTERN,
  EXIF_SCAN_MAX_BYTES,
  GPS_COORDINATE_PARTS,
  GPS_EAST,
  GPS_NORTH,
  GPS_SOUTH,
  GPS_WEST,
  IFD_COUNT_BYTES,
  IFD_ENTRY_BYTES,
  IFD_ENTRY_COUNT_OFFSET,
  IFD_ENTRY_TYPE_OFFSET,
  IFD_ENTRY_VALUE_OFFSET,
  IFD_INLINE_VALUE_BYTES,
  JPEG_LENGTH_FIELD_BYTES,
  JPEG_MARKER_APP1,
  JPEG_MARKER_BYTES,
  JPEG_MARKER_EOI,
  JPEG_MARKER_PREFIX,
  JPEG_MARKER_RST_MAX,
  JPEG_MARKER_RST_MIN,
  JPEG_MARKER_SOS,
  JPEG_MARKER_TEM,
  JPEG_SEGMENT_HEADER_BYTES,
  JPEG_SOI_BYTES,
  JPEG_SOI_SECOND_BYTE,
  LATITUDE_LIMIT,
  LONGITUDE_LIMIT,
  MEDIA_MINUTES_PER_HOUR,
  MEDIA_SECONDS_PER_HOUR,
  ORIENTATION_MAX,
  ORIENTATION_MIN,
  PNG_CHUNK_HEADER_BYTES,
  PNG_CHUNK_TYPE_BYTES,
  PNG_CHUNK_TYPE_OFFSET,
  PNG_CRC_BYTES,
  PNG_END_CHUNK,
  PNG_EXIF_CHUNK,
  PNG_SIGNATURE,
  PNG_SIGNATURE_BYTES,
  RATIONAL_BYTES,
  RATIONAL_DENOMINATOR_OFFSET,
  RIFF_CHUNK_HEADER_BYTES,
  RIFF_CHUNK_SIZE_OFFSET,
  RIFF_HEADER_BYTES,
  RIFF_PAD_ALIGNMENT,
  RIFF_TAG,
  RIFF_WEBP_TAG,
  RIFF_WEBP_TAG_OFFSET,
  TAG_DATE_TIME_ORIGINAL,
  TAG_EXIF_POINTER,
  TAG_GPS_LATITUDE,
  TAG_GPS_LATITUDE_REF,
  TAG_GPS_LONGITUDE,
  TAG_GPS_LONGITUDE_REF,
  TAG_GPS_POINTER,
  TAG_OFFSET_TIME_ORIGINAL,
  TAG_ORIENTATION,
  TIFF_BIG_ENDIAN_MARK,
  TIFF_IFD0_POINTER_OFFSET,
  TIFF_LITTLE_ENDIAN_MARK,
  TIFF_MAGIC,
  TIFF_MAGIC_OFFSET,
  TIFF_ORDER_MARK_BYTES,
  TIFF_ORDER_OFFSET,
  TIFF_TYPE_ASCII,
  TIFF_TYPE_LONG,
  TIFF_TYPE_RATIONAL,
  TIFF_TYPE_SHORT,
  TRAILING_NULS_PATTERN,
  WEBP_EXIF_CHUNK,
} from './constants.ts';
import { MediaRangeError } from './errors.ts';
import { MEDIA_TEXT } from './text.ts';

export type GeoPoint = { readonly latitude: number; readonly longitude: number };

export type ImageMetadata = {
  readonly dateTimeOriginal: string | null;
  readonly offsetTimeOriginal: string | null;
  readonly gps: GeoPoint | null;
  readonly orientation: number | null;
  readonly malformed: boolean;
};

type MutableMetadata = { -readonly [Key in keyof ImageMetadata]: ImageMetadata[Key] };

type IfdEntry = {
  readonly tag: number;
  readonly type: number;
  readonly count: number;
  readonly valueOffset: number;
};

type TiffContext = {
  readonly bytes: Uint8Array;
  readonly little: boolean;
  readonly visited: Set<number>;
};

function emptyMetadata(malformed = false): MutableMetadata {
  return { dateTimeOriginal: null, offsetTimeOriginal: null, gps: null, orientation: null, malformed };
}

function readIfd(context: TiffContext, offset: number): readonly IfdEntry[] {
  if (context.visited.has(offset)) return [];
  context.visited.add(offset);
  const count = readUint16(context.bytes, offset, context.little);
  const limit = Math.min(count, EXIF_MAX_IFD_ENTRIES);
  const entries: IfdEntry[] = [];
  for (let index = 0; index < limit; index += 1) {
    const base = offset + IFD_COUNT_BYTES + index * IFD_ENTRY_BYTES;
    entries.push({
      tag: readUint16(context.bytes, base, context.little),
      type: readUint16(context.bytes, base + IFD_ENTRY_TYPE_OFFSET, context.little),
      count: readUint32(context.bytes, base + IFD_ENTRY_COUNT_OFFSET, context.little),
      valueOffset: base + IFD_ENTRY_VALUE_OFFSET,
    });
  }
  return entries;
}

function entryFor(entries: readonly IfdEntry[], tag: number): IfdEntry | null {
  return entries.find((entry) => entry.tag === tag) ?? null;
}

function dataOffset(context: TiffContext, entry: IfdEntry, byteLength: number): number {
  return byteLength <= IFD_INLINE_VALUE_BYTES
    ? entry.valueOffset
    : readUint32(context.bytes, entry.valueOffset, context.little);
}

function pointerOf(context: TiffContext, entries: readonly IfdEntry[], tag: number): number | null {
  const entry = entryFor(entries, tag);
  if (entry === null || entry.type !== TIFF_TYPE_LONG) return null;
  return readUint32(context.bytes, entry.valueOffset, context.little);
}

function orientationOf(context: TiffContext, entries: readonly IfdEntry[]): number | null {
  const entry = entryFor(entries, TAG_ORIENTATION);
  if (entry === null || entry.type !== TIFF_TYPE_SHORT) return null;
  const value = readUint16(context.bytes, entry.valueOffset, context.little);
  return value >= ORIENTATION_MIN && value <= ORIENTATION_MAX ? value : null;
}

function asciiOf(context: TiffContext, entries: readonly IfdEntry[], tag: number): string | null {
  const entry = entryFor(entries, tag);
  if (entry === null || entry.type !== TIFF_TYPE_ASCII || entry.count > EXIF_ASCII_MAX_BYTES) {
    return null;
  }
  const offset = dataOffset(context, entry, entry.count);
  return readAscii(context.bytes, offset, entry.count).replace(TRAILING_NULS_PATTERN, '');
}

function coordinateOf(
  context: TiffContext,
  entries: readonly IfdEntry[],
  tag: number,
): number | null {
  const entry = entryFor(entries, tag);
  if (entry === null || entry.type !== TIFF_TYPE_RATIONAL || entry.count !== GPS_COORDINATE_PARTS) {
    return null;
  }
  const offset = readUint32(context.bytes, entry.valueOffset, context.little);
  const [degrees, minutes, seconds] = Array.from({ length: GPS_COORDINATE_PARTS }, (_, index) => {
    const base = offset + index * RATIONAL_BYTES;
    const numerator = readUint32(context.bytes, base, context.little);
    const denominator = readUint32(context.bytes, base + RATIONAL_DENOMINATOR_OFFSET, context.little);
    return denominator === 0 ? Number.NaN : numerator / denominator;
  });
  return (
    (degrees ?? Number.NaN) +
    (minutes ?? Number.NaN) / MEDIA_MINUTES_PER_HOUR +
    (seconds ?? Number.NaN) / MEDIA_SECONDS_PER_HOUR
  );
}

function signedBy(value: number | null, ref: string | null, positive: string, negative: string) {
  if (value === null || ref === null) return null;
  if (ref === positive) return value;
  return ref === negative ? 0 - value : null;
}

function usablePoint(latitude: number | null, longitude: number | null): GeoPoint | null {
  if (latitude === null || longitude === null) return null;
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  if (Math.abs(latitude) > LATITUDE_LIMIT || Math.abs(longitude) > LONGITUDE_LIMIT) return null;
  if (latitude === 0 && longitude === 0) return null;
  return { latitude, longitude };
}

function readGps(context: TiffContext, entries: readonly IfdEntry[]): GeoPoint | null {
  const latitude = signedBy(
    coordinateOf(context, entries, TAG_GPS_LATITUDE),
    asciiOf(context, entries, TAG_GPS_LATITUDE_REF),
    GPS_NORTH,
    GPS_SOUTH,
  );
  const longitude = signedBy(
    coordinateOf(context, entries, TAG_GPS_LONGITUDE),
    asciiOf(context, entries, TAG_GPS_LONGITUDE_REF),
    GPS_EAST,
    GPS_WEST,
  );
  return usablePoint(latitude, longitude);
}

function matching(text: string | null, pattern: RegExp): string | null {
  return text !== null && pattern.test(text) ? text : null;
}

function readTiff(bytes: Uint8Array, found: MutableMetadata): void {
  const mark = readAscii(bytes, TIFF_ORDER_OFFSET, TIFF_ORDER_MARK_BYTES);
  if (mark !== TIFF_LITTLE_ENDIAN_MARK && mark !== TIFF_BIG_ENDIAN_MARK) {
    throw new MediaRangeError(MEDIA_TEXT.malformedStructure);
  }
  const little = mark === TIFF_LITTLE_ENDIAN_MARK;
  if (readUint16(bytes, TIFF_MAGIC_OFFSET, little) !== TIFF_MAGIC) {
    throw new MediaRangeError(MEDIA_TEXT.malformedStructure);
  }
  const context: TiffContext = { bytes, little, visited: new Set() };
  const ifd0 = readIfd(context, readUint32(bytes, TIFF_IFD0_POINTER_OFFSET, little));
  found.orientation = orientationOf(context, ifd0);
  const exifPointer = pointerOf(context, ifd0, TAG_EXIF_POINTER);
  if (exifPointer !== null) {
    const exif = readIfd(context, exifPointer);
    found.dateTimeOriginal = matching(
      asciiOf(context, exif, TAG_DATE_TIME_ORIGINAL),
      EXIF_DATE_PATTERN,
    );
    found.offsetTimeOriginal = matching(
      asciiOf(context, exif, TAG_OFFSET_TIME_ORIGINAL),
      EXIF_OFFSET_PATTERN,
    );
  }
  const gpsPointer = pointerOf(context, ifd0, TAG_GPS_POINTER);
  if (gpsPointer !== null) found.gps = readGps(context, readIfd(context, gpsPointer));
}

export function parseTiffExif(tiff: Uint8Array): ImageMetadata {
  const found = emptyMetadata();
  try {
    readTiff(tiff, found);
  } catch {
    found.malformed = true;
  }
  return found;
}

function isStandaloneMarker(marker: number): boolean {
  return (
    marker === JPEG_MARKER_TEM ||
    (marker >= JPEG_MARKER_RST_MIN && marker <= JPEG_MARKER_RST_MAX)
  );
}

function locateJpeg(bytes: Uint8Array): Uint8Array | null {
  if (
    readUint8(bytes, 0) !== JPEG_MARKER_PREFIX ||
    readUint8(bytes, 1) !== JPEG_SOI_SECOND_BYTE
  ) {
    throw new MediaRangeError(MEDIA_TEXT.malformedStructure);
  }
  let position = JPEG_SOI_BYTES;
  while (position < bytes.length) {
    if (readUint8(bytes, position) !== JPEG_MARKER_PREFIX) {
      throw new MediaRangeError(MEDIA_TEXT.malformedStructure);
    }
    const marker = readUint8(bytes, position + 1);
    if (marker === JPEG_MARKER_SOS || marker === JPEG_MARKER_EOI) return null;
    if (isStandaloneMarker(marker)) {
      position += JPEG_MARKER_BYTES;
      continue;
    }
    const length = readUint16(bytes, position + JPEG_MARKER_BYTES);
    if (length < JPEG_LENGTH_FIELD_BYTES) throw new MediaRangeError(MEDIA_TEXT.malformedStructure);
    const end = position + JPEG_MARKER_BYTES + length;
    const payload = sliceRange(bytes, position + JPEG_SEGMENT_HEADER_BYTES, end);
    if (marker === JPEG_MARKER_APP1 && startsWithAscii(payload, 0, EXIF_IDENTIFIER)) {
      return payload.subarray(EXIF_IDENTIFIER.length);
    }
    position = end;
  }
  return null;
}

function locatePng(bytes: Uint8Array): Uint8Array | null {
  if (!startsWithBytes(bytes, 0, PNG_SIGNATURE)) {
    throw new MediaRangeError(MEDIA_TEXT.malformedStructure);
  }
  let position = PNG_SIGNATURE_BYTES;
  while (position < bytes.length) {
    const length = readUint32(bytes, position);
    const type = readAscii(bytes, position + PNG_CHUNK_TYPE_OFFSET, PNG_CHUNK_TYPE_BYTES);
    const dataStart = position + PNG_CHUNK_HEADER_BYTES;
    const dataEnd = dataStart + length;
    const data = sliceRange(bytes, dataStart, dataEnd);
    if (type === PNG_EXIF_CHUNK) return data;
    if (type === PNG_END_CHUNK) return null;
    position = dataEnd + PNG_CRC_BYTES;
  }
  return null;
}

function locateWebp(bytes: Uint8Array): Uint8Array | null {
  if (
    !startsWithAscii(bytes, 0, RIFF_TAG) ||
    !startsWithAscii(bytes, RIFF_WEBP_TAG_OFFSET, RIFF_WEBP_TAG)
  ) {
    throw new MediaRangeError(MEDIA_TEXT.malformedStructure);
  }
  let position = RIFF_HEADER_BYTES;
  while (position < bytes.length) {
    const type = readAscii(bytes, position, PNG_CHUNK_TYPE_BYTES);
    const size = readUint32(bytes, position + RIFF_CHUNK_SIZE_OFFSET, true);
    const dataEnd = position + RIFF_CHUNK_HEADER_BYTES + size;
    const data = sliceRange(bytes, position + RIFF_CHUNK_HEADER_BYTES, dataEnd);
    if (type === WEBP_EXIF_CHUNK) {
      return startsWithAscii(data, 0, EXIF_IDENTIFIER)
        ? data.subarray(EXIF_IDENTIFIER.length)
        : data;
    }
    position = dataEnd + (size % RIFF_PAD_ALIGNMENT);
  }
  return null;
}

const LOCATORS: Readonly<
  Record<'jpeg' | 'png' | 'webp', (bytes: Uint8Array) => Uint8Array | null>
> = Object.freeze({ jpeg: locateJpeg, png: locatePng, webp: locateWebp });

export async function readImageMetadata(
  source: ByteSource,
  container: 'jpeg' | 'png' | 'webp',
): Promise<ImageMetadata> {
  let limited = false;
  try {
    const window = await source.read(0, Math.min(source.size, EXIF_SCAN_MAX_BYTES));
    limited = source.size > window.length;
    const tiff = LOCATORS[container](window);
    return tiff === null ? emptyMetadata() : parseTiffExif(tiff);
  } catch (error) {
    return emptyMetadata(!(error instanceof MediaRangeError && limited));
  }
}

export function hasExifSegment(bytes: Uint8Array): boolean {
  try {
    return locateJpeg(bytes) !== null;
  } catch {
    return true;
  }
}

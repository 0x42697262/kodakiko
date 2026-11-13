import { readAscii, readUint16, readUint32, readUint64, readUint8, requireRange } from './binary.ts';
import type { ByteSource } from './byte-source.ts';
import {
  APPLE_LOCATION_KEY,
  BOX_DATA,
  BOX_HDLR,
  BOX_MDIA,
  BOX_TKHD,
  BOX_TRAK,
  HANDLER_VIDEO,
  HDLR_HANDLER_BYTES,
  HDLR_HANDLER_OFFSET,
  INT32_MAX,
  ISO6709_MAX_BYTES,
  KEYS_NAME_MAX_BYTES,
  MATRIX_A_OFFSET,
  MATRIX_B_OFFSET,
  MATRIX_C_OFFSET,
  MATRIX_D_OFFSET,
  MAX_ISO_YEAR,
  ROTATION_MATRICES,
  TKHD_MATRIX_OFFSET_V0,
  TKHD_MATRIX_OFFSET_V1,
  TKHD_VERSION_1,
  UINT32_RANGE,
  type VideoRotation,
  BOX_HEADER_BYTES,
  BOX_ILST,
  BOX_KEYS,
  BOX_LARGE_HEADER_BYTES,
  BOX_LARGE_SIZE_MARKER,
  BOX_LARGE_SIZE_OFFSET,
  BOX_META,
  BOX_MOOV,
  BOX_MVHD,
  BOX_PEEK_BYTES,
  BOX_TO_END_MARKER,
  BOX_TYPE_BYTES,
  BOX_TYPE_OFFSET,
  BOX_UDTA,
  BOX_WALK_MAX_BOXES,
  BOX_XYZ,
  DATA_BOX_VALUE_OFFSET,
  FULL_BOX_HEADER_BYTES,
  ISO6709_PATTERN,
  KEYS_COUNT_OFFSET,
  KEYS_ENTRY_NAMESPACE_BYTES,
  KEYS_ENTRY_SIZE_BYTES,
  KEYS_FIRST_ENTRY_OFFSET,
  LATITUDE_LIMIT,
  LONGITUDE_LIMIT,
  MEDIA_MS_PER_SECOND,
  MOOV_MAX_BYTES,
  MP4_EPOCH_OFFSET_SECONDS,
  MVHD_CREATION_OFFSET,
  MVHD_VERSION_1,
  TRAILING_NULS_PATTERN,
  XYZ_LANGUAGE_BYTES,
  XYZ_LENGTH_BYTES,
} from './constants.ts';
import { MediaRangeError } from './errors.ts';
import type { GeoPoint } from './exif.ts';
import { MEDIA_TEXT } from './text.ts';

export type VideoMetadata = {
  readonly creationUtc: string | null;
  readonly location: GeoPoint | null;
  readonly rotationDegrees: VideoRotation;
  readonly malformed: boolean;
};

type MutableVideoMetadata = { -readonly [Key in keyof VideoMetadata]: VideoMetadata[Key] };

type BoxHeader = {
  readonly type: string;
  readonly typeCode: number;
  readonly headerSize: number;
  readonly size: number;
};

type ChildBox = {
  readonly type: string;
  readonly typeCode: number;
  readonly payload: Uint8Array;
};

function parseBoxHeader(peek: Uint8Array, available: number): BoxHeader {
  if (peek.length < BOX_HEADER_BYTES) throw new MediaRangeError(MEDIA_TEXT.outOfRange);
  const declared = readUint32(peek, 0);
  const type = readAscii(peek, BOX_TYPE_OFFSET, BOX_TYPE_BYTES);
  const typeCode = readUint32(peek, BOX_TYPE_OFFSET);
  const large = declared === BOX_LARGE_SIZE_MARKER;
  const headerSize = large ? BOX_LARGE_HEADER_BYTES : BOX_HEADER_BYTES;
  let size = declared;
  if (large) size = readUint64(peek, BOX_LARGE_SIZE_OFFSET);
  if (declared === BOX_TO_END_MARKER) size = available;
  if (size < headerSize || size > available) {
    throw new MediaRangeError(MEDIA_TEXT.malformedStructure);
  }
  return { type, typeCode, headerSize, size };
}

function* childBoxes(bytes: Uint8Array): Generator<ChildBox> {
  let position = 0;
  while (position < bytes.length) {
    const peek = bytes.subarray(position, position + BOX_PEEK_BYTES);
    const header = parseBoxHeader(peek, bytes.length - position);
    yield {
      type: header.type,
      typeCode: header.typeCode,
      payload: bytes.subarray(position + header.headerSize, position + header.size),
    };
    position += header.size;
  }
}

export function parseIso6709(text: string): GeoPoint | null {
  if (text.length > ISO6709_MAX_BYTES) return null;
  const match = ISO6709_PATTERN.exec(text.replace(TRAILING_NULS_PATTERN, '').trim());
  if (match === null) return null;
  const latitude = Number.parseFloat(match[1] ?? '');
  const longitude = Number.parseFloat(match[2] ?? '');
  if (Math.abs(latitude) > LATITUDE_LIMIT || Math.abs(longitude) > LONGITUDE_LIMIT) return null;
  return { latitude, longitude };
}

function creationTime(mvhdPayload: Uint8Array): string | null {
  const version = readUint8(mvhdPayload, 0);
  const seconds =
    version === MVHD_VERSION_1
      ? readUint64(mvhdPayload, MVHD_CREATION_OFFSET)
      : readUint32(mvhdPayload, MVHD_CREATION_OFFSET);
  if (seconds === 0) return null;
  const date = new Date((seconds - MP4_EPOCH_OFFSET_SECONDS) * MEDIA_MS_PER_SECOND);
  if (Number.isNaN(date.getTime()) || date.getUTCFullYear() > MAX_ISO_YEAR) return null;
  return date.toISOString();
}

function xyzLocation(payload: Uint8Array): GeoPoint | null {
  const length = readUint16(payload, 0);
  if (length > ISO6709_MAX_BYTES) return null;
  const start = XYZ_LENGTH_BYTES + XYZ_LANGUAGE_BYTES;
  const available = Math.max(0, payload.length - start);
  return parseIso6709(readAscii(payload, start, Math.min(length, available)));
}

function keyNames(payload: Uint8Array): readonly string[] {
  const count = readUint32(payload, KEYS_COUNT_OFFSET);
  const names: string[] = [];
  let position = KEYS_FIRST_ENTRY_OFFSET;
  for (let index = 0; index < count && position < payload.length; index += 1) {
    const size = readUint32(payload, position);
    const nameStart = position + KEYS_ENTRY_SIZE_BYTES + KEYS_ENTRY_NAMESPACE_BYTES;
    if (size < nameStart - position) throw new MediaRangeError(MEDIA_TEXT.malformedStructure);
    const nameBytes = position + size - nameStart;
    names.push(nameBytes > KEYS_NAME_MAX_BYTES ? '' : readAscii(payload, nameStart, nameBytes));
    position += size;
  }
  return names;
}

function itemText(itemPayload: Uint8Array): string | null {
  for (const child of childBoxes(itemPayload)) {
    if (child.type === BOX_DATA && child.payload.length >= DATA_BOX_VALUE_OFFSET) {
      if (child.payload.length - DATA_BOX_VALUE_OFFSET > ISO6709_MAX_BYTES) return null;
      return readAscii(
        child.payload,
        DATA_BOX_VALUE_OFFSET,
        child.payload.length - DATA_BOX_VALUE_OFFSET,
      );
    }
  }
  return null;
}

function appleLocation(metaPayload: Uint8Array): GeoPoint | null {
  const fullBox = metaPayload.length >= FULL_BOX_HEADER_BYTES && readUint32(metaPayload, 0) === 0;
  const children = [...childBoxes(metaPayload.subarray(fullBox ? FULL_BOX_HEADER_BYTES : 0))];
  const keys = children.find((child) => child.type === BOX_KEYS);
  const items = children.find((child) => child.type === BOX_ILST);
  if (keys === undefined || items === undefined) return null;
  const names = keyNames(keys.payload);
  for (const item of childBoxes(items.payload)) {
    if (names[item.typeCode - 1] !== APPLE_LOCATION_KEY) continue;
    const text = itemText(item.payload);
    const point = text === null ? null : parseIso6709(text);
    if (point !== null) return point;
  }
  return null;
}

function signedCell(payload: Uint8Array, offset: number): number {
  const value = readUint32(payload, offset);
  return value > INT32_MAX ? value - UINT32_RANGE : value;
}

function trackRotation(tkhdPayload: Uint8Array): VideoRotation {
  const base =
    readUint8(tkhdPayload, 0) === TKHD_VERSION_1 ? TKHD_MATRIX_OFFSET_V1 : TKHD_MATRIX_OFFSET_V0;
  const cells = [MATRIX_A_OFFSET, MATRIX_B_OFFSET, MATRIX_C_OFFSET, MATRIX_D_OFFSET].map((offset) =>
    signedCell(tkhdPayload, base + offset),
  );
  const match = ROTATION_MATRICES.find((entry) =>
    entry.cells.every((cell, index) => cell === cells[index]),
  );
  return match?.degrees ?? 0;
}

function handlerType(mdiaPayload: Uint8Array): string | null {
  for (const child of childBoxes(mdiaPayload)) {
    if (child.type === BOX_HDLR) {
      return readAscii(child.payload, HDLR_HANDLER_OFFSET, HDLR_HANDLER_BYTES);
    }
  }
  return null;
}

function videoTrackRotation(trakPayload: Uint8Array): VideoRotation | null {
  let rotation: VideoRotation | null = null;
  let handler: string | null = null;
  for (const child of childBoxes(trakPayload)) {
    if (child.type === BOX_TKHD) rotation = trackRotation(child.payload);
    if (child.type === BOX_MDIA) handler = handlerType(child.payload);
  }
  return handler === HANDLER_VIDEO ? (rotation ?? 0) : null;
}

function readMoov(body: Uint8Array, found: MutableVideoMetadata): void {
  let rotationFound = false;
  for (const child of childBoxes(body)) {
    if (child.type === BOX_MVHD) {
      found.creationUtc = creationTime(child.payload);
    } else if (child.type === BOX_TRAK && !rotationFound) {
      const rotation = videoTrackRotation(child.payload);
      if (rotation !== null) {
        found.rotationDegrees = rotation;
        rotationFound = true;
      }
    } else if (child.type === BOX_UDTA) {
      for (const entry of childBoxes(child.payload)) {
        if (entry.type === BOX_XYZ) found.location ??= xyzLocation(entry.payload);
      }
    } else if (child.type === BOX_META) {
      found.location ??= appleLocation(child.payload);
    }
  }
}

async function walkTopLevel(source: ByteSource, found: MutableVideoMetadata): Promise<void> {
  let offset = 0;
  for (let count = 0; count < BOX_WALK_MAX_BOXES && offset < source.size; count += 1) {
    const peek = await source.read(offset, BOX_PEEK_BYTES);
    const header = parseBoxHeader(peek, source.size - offset);
    if (header.type === BOX_MOOV) {
      if (header.size > MOOV_MAX_BYTES) throw new MediaRangeError(MEDIA_TEXT.malformedStructure);
      const bodyLength = header.size - header.headerSize;
      const body = await source.read(offset + header.headerSize, bodyLength);
      requireRange(body, 0, bodyLength);
      readMoov(body, found);
      return;
    }
    offset += header.size;
  }
}

export async function readVideoMetadata(source: ByteSource): Promise<VideoMetadata> {
  const found: MutableVideoMetadata = {
    creationUtc: null,
    location: null,
    rotationDegrees: 0,
    malformed: false,
  };
  try {
    await walkTopLevel(source, found);
  } catch {
    found.malformed = true;
  }
  return found;
}

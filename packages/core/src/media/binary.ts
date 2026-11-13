import {
  MEDIA_BYTE_RADIX,
  MEDIA_UINT16_BYTES,
  MEDIA_UINT32_BYTES,
  MEDIA_UINT64_BYTES,
  MEDIA_UINT8_BYTES,
} from './constants.ts';
import { MediaRangeError } from './errors.ts';
import { MEDIA_TEXT } from './text.ts';

export function requireRange(bytes: Uint8Array, offset: number, length: number): void {
  if (
    !Number.isSafeInteger(offset) ||
    !Number.isSafeInteger(length) ||
    offset < 0 ||
    length < 0 ||
    offset + length > bytes.length
  ) {
    throw new MediaRangeError(MEDIA_TEXT.outOfRange);
  }
}

export function sliceRange(bytes: Uint8Array, start: number, end: number): Uint8Array {
  requireRange(bytes, start, end - start);
  return bytes.subarray(start, end);
}

function readUnsigned(
  bytes: Uint8Array,
  offset: number,
  length: number,
  littleEndian: boolean,
): number {
  requireRange(bytes, offset, length);
  let value = 0;
  for (let step = 0; step < length; step += 1) {
    const index = littleEndian ? offset + length - 1 - step : offset + step;
    value = value * MEDIA_BYTE_RADIX + (bytes[index] ?? 0);
  }
  if (!Number.isSafeInteger(value)) throw new MediaRangeError(MEDIA_TEXT.outOfRange);
  return value;
}

export function readUint8(bytes: Uint8Array, offset: number): number {
  return readUnsigned(bytes, offset, MEDIA_UINT8_BYTES, false);
}

export function readUint16(bytes: Uint8Array, offset: number, littleEndian = false): number {
  return readUnsigned(bytes, offset, MEDIA_UINT16_BYTES, littleEndian);
}

export function readUint32(bytes: Uint8Array, offset: number, littleEndian = false): number {
  return readUnsigned(bytes, offset, MEDIA_UINT32_BYTES, littleEndian);
}

export function readUint64(bytes: Uint8Array, offset: number): number {
  return readUnsigned(bytes, offset, MEDIA_UINT64_BYTES, false);
}

export function readAscii(bytes: Uint8Array, offset: number, length: number): string {
  requireRange(bytes, offset, length);
  let text = '';
  for (let index = 0; index < length; index += 1) {
    text += String.fromCharCode(bytes[offset + index] ?? 0);
  }
  return text;
}

export function startsWithBytes(bytes: Uint8Array, offset: number, expected: readonly number[]): boolean {
  if (offset < 0 || offset + expected.length > bytes.length) return false;
  return expected.every((value, index) => bytes[offset + index] === value);
}

export function startsWithAscii(bytes: Uint8Array, offset: number, expected: string): boolean {
  if (offset < 0 || offset + expected.length > bytes.length) return false;
  return readAscii(bytes, offset, expected.length) === expected;
}

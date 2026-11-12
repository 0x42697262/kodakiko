import {
  BYTE_VALUES,
  HEX_CHARS_PER_BYTE,
  HEX_RADIX,
  STREAM_COUNTER_BYTES,
  STREAM_MAX_CHUNK_INDEX,
} from './constants.ts';
import { InvalidChunkError, MalformedPayloadError } from './errors.ts';
import { CRYPTO_TEXT } from './text.ts';

const HEX_PATTERN = /^[0-9a-fA-F]*$/;

export function concatBytes(...parts: readonly Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

export function utf8Encode(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

export function utf8Decode(bytes: Uint8Array): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  }
}

export function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference |= (left[index] ?? 0) ^ (right[index] ?? 0);
  }
  return difference === 0;
}

export function writeUint56BigEndian(target: Uint8Array, offset: number, value: number): void {
  if (!Number.isInteger(value) || value < 0 || value > STREAM_MAX_CHUNK_INDEX) {
    throw new InvalidChunkError(CRYPTO_TEXT.invalidChunk);
  }
  let remaining = value;
  for (let position = STREAM_COUNTER_BYTES - 1; position >= 0; position -= 1) {
    target[offset + position] = remaining % BYTE_VALUES;
    remaining = Math.floor(remaining / BYTE_VALUES);
  }
}

export function wipe(bytes: Uint8Array): void {
  bytes.fill(0);
}

export function bytesToHex(bytes: Uint8Array): string {
  let hex = '';
  for (const byte of bytes) {
    hex += byte.toString(HEX_RADIX).padStart(HEX_CHARS_PER_BYTE, '0');
  }
  return hex;
}

export function hexToBytes(hex: string): Uint8Array {
  if (hex.length % HEX_CHARS_PER_BYTE !== 0 || !HEX_PATTERN.test(hex)) {
    throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  }
  const bytes = new Uint8Array(hex.length / HEX_CHARS_PER_BYTE);
  for (let index = 0; index < bytes.length; index += 1) {
    const start = index * HEX_CHARS_PER_BYTE;
    bytes[index] = Number.parseInt(hex.slice(start, start + HEX_CHARS_PER_BYTE), HEX_RADIX);
  }
  return bytes;
}

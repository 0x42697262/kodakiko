import {
  BASE64URL_ALPHABET,
  BITS_PER_BYTE,
  BITS_PER_CHAR,
  BYTE_MASK,
  BYTES_FROM_THREE_CHARS,
  BYTES_FROM_TWO_CHARS,
  BYTES_PER_GROUP,
  CHAR_MASK,
  CHARS_FOR_TWO_BYTES_GROUP,
  CHARS_PER_GROUP,
  ORPHAN_CHARS_LENGTH_REMAINDER,
} from './constants.ts';
import { BASE64URL_REGEX, type Bytes } from './ids.ts';

const CHAR_VALUES: ReadonlyMap<string, number> = new Map(
  [...BASE64URL_ALPHABET].map((character, position) => [character, position]),
);

export function isDecodableBase64Url(value: string): boolean {
  return (
    BASE64URL_REGEX.test(value) && value.length % CHARS_PER_GROUP !== ORPHAN_CHARS_LENGTH_REMAINDER
  );
}

export function encodeBytes(bytes: Uint8Array): Bytes {
  let encoded = '';
  for (let start = 0; start < bytes.length; start += BYTES_PER_GROUP) {
    const available = Math.min(BYTES_PER_GROUP, bytes.length - start);
    let bits = 0;
    for (let offset = 0; offset < BYTES_PER_GROUP; offset += 1) {
      bits = (bits << BITS_PER_BYTE) | (bytes[start + offset] ?? 0);
    }
    for (let position = 0; position <= available; position += 1) {
      const shift = BITS_PER_CHAR * (CHARS_PER_GROUP - 1 - position);
      encoded += BASE64URL_ALPHABET.charAt((bits >> shift) & CHAR_MASK);
    }
  }
  return encoded as Bytes;
}

export function decodeBytes(value: Bytes): Uint8Array {
  if (!isDecodableBase64Url(value)) {
    throw new RangeError('Value is not unpadded base64url');
  }
  const output: number[] = [];
  for (let start = 0; start < value.length; start += CHARS_PER_GROUP) {
    const group = value.slice(start, start + CHARS_PER_GROUP);
    let bits = 0;
    for (let position = 0; position < CHARS_PER_GROUP; position += 1) {
      bits = (bits << BITS_PER_CHAR) | (CHAR_VALUES.get(group.charAt(position)) ?? 0);
    }
    const byteCount = bytesInGroup(group.length);
    for (let index = 0; index < byteCount; index += 1) {
      const shift = BITS_PER_BYTE * (BYTES_PER_GROUP - 1 - index);
      output.push((bits >> shift) & BYTE_MASK);
    }
  }
  return Uint8Array.from(output);
}

function bytesInGroup(characters: number): number {
  if (characters === CHARS_PER_GROUP) {
    return BYTES_PER_GROUP;
  }
  return characters === CHARS_FOR_TWO_BYTES_GROUP ? BYTES_FROM_THREE_CHARS : BYTES_FROM_TWO_CHARS;
}

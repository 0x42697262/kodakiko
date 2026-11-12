import { PADDING_MARKER } from './constants.ts';
import { MalformedPayloadError } from './errors.ts';
import { CRYPTO_TEXT } from './text.ts';

export function padIso7816(bytes: Uint8Array, blockBytes: number): Uint8Array {
  const paddedLength = (Math.floor(bytes.length / blockBytes) + 1) * blockBytes;
  const padded = new Uint8Array(paddedLength);
  padded.set(bytes);
  padded[bytes.length] = PADDING_MARKER;
  return padded;
}

export function unpadIso7816(bytes: Uint8Array, blockBytes: number): Uint8Array {
  if (bytes.length === 0 || bytes.length % blockBytes !== 0) {
    throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  }
  let end = bytes.length;
  while (end > 0 && bytes[end - 1] === 0) {
    end -= 1;
  }
  if (end === 0 || bytes[end - 1] !== PADDING_MARKER) {
    throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  }
  return bytes.slice(0, end - 1);
}

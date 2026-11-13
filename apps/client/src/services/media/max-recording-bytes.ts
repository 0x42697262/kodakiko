import {
  AEAD_TAG_BYTES,
  STREAM_CHUNK_SIZE_BASE,
  STREAM_CHUNK_SIZE_EXPONENT,
  STREAM_HEADER_BYTES,
} from '@kodakiko/core';

export function maxPlaintextBytes(maxCiphertextBytes: number): number {
  const chunkSize = STREAM_CHUNK_SIZE_BASE ** STREAM_CHUNK_SIZE_EXPONENT;
  const body = maxCiphertextBytes - STREAM_HEADER_BYTES;
  if (body <= 0) return 0;
  const fullChunks = Math.floor(body / (chunkSize + AEAD_TAG_BYTES));
  const rest = body - fullChunks * (chunkSize + AEAD_TAG_BYTES);
  return fullChunks * chunkSize + Math.max(0, rest - AEAD_TAG_BYTES);
}

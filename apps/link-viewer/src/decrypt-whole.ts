import {
  STREAM_HEADER_BYTES,
  concatBytes,
  createStreamDecryptor,
  type Sodium,
} from '@kodakiko/core';

export function decryptWhole(
  sodium: Sodium,
  variantKey: Uint8Array,
  ciphertext: Uint8Array,
  plaintextSize: number,
): Uint8Array {
  const decryptor = createStreamDecryptor(
    sodium,
    variantKey,
    ciphertext.subarray(0, STREAM_HEADER_BYTES),
    plaintextSize,
  );
  const { chunkCount, chunkCiphertextSize } = decryptor.layout;
  const parts: Uint8Array[] = [];
  for (let index = 0; index < chunkCount; index += 1) {
    const start = STREAM_HEADER_BYTES + index * chunkCiphertextSize;
    parts.push(decryptor.decryptNext(ciphertext.subarray(start, start + chunkCiphertextSize)));
  }
  decryptor.finish();
  return concatBytes(...parts);
}

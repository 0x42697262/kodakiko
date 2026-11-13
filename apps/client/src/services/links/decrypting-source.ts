import { createStreamDecryptor, decryptChunk, STREAM_HEADER_BYTES, streamLayout, parseStreamHeader, type ByteSource, type Sodium } from '@kodakiko/core';
import type { FileStore } from '../../adapters/filestore/filestore';

const CHUNK_CACHE_ITEMS = 4;

export async function createDecryptingByteSource(
  sodium: Sodium,
  fileStore: FileStore,
  objectId: string,
  variantKey: Uint8Array,
  plaintextSize: number,
): Promise<ByteSource> {
  const header = await fileStore.readRange(objectId, 0, STREAM_HEADER_BYTES);
  createStreamDecryptor(sodium, variantKey, header, plaintextSize);
  const layout = streamLayout(plaintextSize, parseStreamHeader(header).chunkSizeExponent);
  const cache = new Map<number, Uint8Array>();

  async function chunk(index: number): Promise<Uint8Array> {
    const cached = cache.get(index);
    if (cached !== undefined) {
      cache.delete(index);
      cache.set(index, cached);
      return cached;
    }
    const offset = STREAM_HEADER_BYTES + index * layout.chunkCiphertextSize;
    const length = Math.min(layout.chunkCiphertextSize, layout.ciphertextSize - offset);
    const ciphertext = await fileStore.readRange(objectId, offset, length);
    const plain = decryptChunk(sodium, variantKey, header, index, index === layout.chunkCount - 1, ciphertext);
    cache.set(index, plain);
    while (cache.size > CHUNK_CACHE_ITEMS) {
      const oldest = cache.keys().next();
      if (oldest.done === true) break;
      cache.get(oldest.value)?.fill(0);
      cache.delete(oldest.value);
    }
    return plain;
  }

  return {
    size: plaintextSize,
    read: async (offset, length) => {
      const start = Math.max(0, offset);
      const end = Math.min(plaintextSize, start + Math.max(0, length));
      if (start >= end) return new Uint8Array(0);
      const out = new Uint8Array(end - start);
      for (let index = Math.floor(start / layout.chunkSize); index * layout.chunkSize < end; index += 1) {
        const plain = await chunk(index);
        const base = index * layout.chunkSize;
        const from = Math.max(start, base);
        const to = Math.min(end, base + plain.length);
        out.set(plain.subarray(from - base, to - base), from - start);
      }
      return out;
    },
  };
}

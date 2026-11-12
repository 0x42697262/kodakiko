import { bytesEqual, concatBytes, writeUint56BigEndian } from './bytes.ts';
import {
  AEAD_TAG_BYTES,
  KEY_BYTES,
  STREAM_CHUNK_SIZE_BASE,
  STREAM_CHUNK_SIZE_EXPONENT,
  STREAM_CHUNK_SIZE_EXPONENT_MAX,
  STREAM_CHUNK_SIZE_EXPONENT_MIN,
  STREAM_COUNTER_BYTES,
  STREAM_FINAL_FLAG,
  STREAM_HEADER_BYTES,
  STREAM_HEADER_EXPONENT_OFFSET,
  STREAM_HEADER_MAGIC_OFFSET,
  STREAM_HEADER_PREFIX_OFFSET,
  STREAM_HEADER_RESERVED_BYTES,
  STREAM_HEADER_RESERVED_OFFSET,
  STREAM_HEADER_VERSION_OFFSET,
  STREAM_MAGIC,
  STREAM_MIN_OBJECT_BYTES,
  STREAM_NONCE_PREFIX_BYTES,
  STREAM_NOT_FINAL_FLAG,
  STREAM_VERSION,
} from './constants.ts';
import {
  InvalidChunkError,
  InvalidKeyLengthError,
  MalformedObjectError,
  TruncatedObjectError,
  UnsupportedFormatError,
} from './errors.ts';
import type { Sodium } from './sodium.ts';
import { CRYPTO_TEXT } from './text.ts';

export type StreamHeader = {
  readonly version: number;
  readonly chunkSizeExponent: number;
  readonly noncePrefix: Uint8Array;
};

export type StreamLayout = {
  readonly plaintextSize: number;
  readonly chunkSizeExponent: number;
  readonly chunkSize: number;
  readonly chunkCount: number;
  readonly chunkCiphertextSize: number;
  readonly ciphertextSize: number;
};

export type ChunkRange = { readonly firstChunk: number; readonly lastChunk: number };

export type StreamEncryptor = {
  readonly header: Uint8Array;
  readonly layout: StreamLayout;
  readonly encryptNext: (plaintextChunk: Uint8Array) => Uint8Array;
  readonly isDone: () => boolean;
};

export type StreamDecryptor = {
  readonly layout: StreamLayout;
  readonly decryptNext: (ciphertextChunk: Uint8Array) => Uint8Array;
  readonly finish: () => void;
};

export function createStreamHeader(sodium: Sodium): Uint8Array {
  return concatBytes(
    STREAM_MAGIC,
    Uint8Array.of(STREAM_VERSION, STREAM_CHUNK_SIZE_EXPONENT),
    new Uint8Array(STREAM_HEADER_RESERVED_BYTES),
    sodium.randomBytes(STREAM_NONCE_PREFIX_BYTES),
  );
}

export function parseStreamHeader(bytes: Uint8Array): StreamHeader {
  const magic = bytes.slice(
    STREAM_HEADER_MAGIC_OFFSET,
    STREAM_HEADER_MAGIC_OFFSET + STREAM_MAGIC.length,
  );
  if (bytes.length !== STREAM_HEADER_BYTES || !bytesEqual(magic, STREAM_MAGIC)) {
    throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
  }
  const version = bytes[STREAM_HEADER_VERSION_OFFSET] ?? 0;
  if (version !== STREAM_VERSION) throw new UnsupportedFormatError(CRYPTO_TEXT.unsupportedFormat);
  const reserved = bytes.slice(
    STREAM_HEADER_RESERVED_OFFSET,
    STREAM_HEADER_RESERVED_OFFSET + STREAM_HEADER_RESERVED_BYTES,
  );
  if (reserved.some((byte) => byte !== 0))
    throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
  const chunkSizeExponent = bytes[STREAM_HEADER_EXPONENT_OFFSET] ?? 0;
  if (
    chunkSizeExponent < STREAM_CHUNK_SIZE_EXPONENT_MIN ||
    chunkSizeExponent > STREAM_CHUNK_SIZE_EXPONENT_MAX
  ) {
    throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
  }
  return {
    version,
    chunkSizeExponent,
    noncePrefix: bytes.slice(
      STREAM_HEADER_PREFIX_OFFSET,
      STREAM_HEADER_PREFIX_OFFSET + STREAM_NONCE_PREFIX_BYTES,
    ),
  };
}

function requireChunkSizeExponent(chunkSizeExponent: number): void {
  if (
    chunkSizeExponent < STREAM_CHUNK_SIZE_EXPONENT_MIN ||
    chunkSizeExponent > STREAM_CHUNK_SIZE_EXPONENT_MAX
  ) {
    throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
  }
}

function requireVariantKeyLength(variantKey: Uint8Array): void {
  if (variantKey.length !== KEY_BYTES)
    throw new InvalidKeyLengthError(CRYPTO_TEXT.invalidKeyLength);
}

export function streamLayout(plaintextSize: number, chunkSizeExponent: number): StreamLayout {
  if (!Number.isSafeInteger(plaintextSize) || plaintextSize < 0) {
    throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
  }
  requireChunkSizeExponent(chunkSizeExponent);
  const chunkSize = STREAM_CHUNK_SIZE_BASE ** chunkSizeExponent;
  const chunkCount = Math.max(1, Math.ceil(plaintextSize / chunkSize));
  return {
    plaintextSize,
    chunkSizeExponent,
    chunkSize,
    chunkCount,
    chunkCiphertextSize: chunkSize + AEAD_TAG_BYTES,
    ciphertextSize: STREAM_HEADER_BYTES + plaintextSize + AEAD_TAG_BYTES * chunkCount,
  };
}

export function layoutFromCiphertextSize(
  ciphertextSize: number,
  chunkSizeExponent: number,
): StreamLayout {
  requireChunkSizeExponent(chunkSizeExponent);
  if (ciphertextSize < STREAM_MIN_OBJECT_BYTES) {
    throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
  }
  const chunkCiphertextSize = STREAM_CHUNK_SIZE_BASE ** chunkSizeExponent + AEAD_TAG_BYTES;
  const chunkCount = Math.ceil((ciphertextSize - STREAM_HEADER_BYTES) / chunkCiphertextSize);
  const plaintextSize = ciphertextSize - STREAM_HEADER_BYTES - AEAD_TAG_BYTES * chunkCount;
  if (chunkCount < 1 || plaintextSize < 0) {
    throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
  }
  const layout = streamLayout(plaintextSize, chunkSizeExponent);
  if (layout.ciphertextSize !== ciphertextSize) {
    throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
  }
  return layout;
}

export function chunkRangeForPlaintextRange(
  layout: StreamLayout,
  start: number,
  endInclusive: number,
): ChunkRange {
  if (
    !Number.isSafeInteger(start) ||
    !Number.isSafeInteger(endInclusive) ||
    start < 0 ||
    start > endInclusive ||
    endInclusive >= layout.plaintextSize
  ) {
    throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
  }
  return {
    firstChunk: Math.floor(start / layout.chunkSize),
    lastChunk: Math.floor(endInclusive / layout.chunkSize),
  };
}

export function streamNonce(header: StreamHeader, index: number, isFinal: boolean): Uint8Array {
  const counter = new Uint8Array(STREAM_COUNTER_BYTES);
  writeUint56BigEndian(counter, 0, index);
  return concatBytes(
    header.noncePrefix,
    counter,
    Uint8Array.of(isFinal ? STREAM_FINAL_FLAG : STREAM_NOT_FINAL_FLAG),
  );
}

export function encryptChunk(
  sodium: Sodium,
  variantKey: Uint8Array,
  header: Uint8Array,
  index: number,
  isFinal: boolean,
  plaintext: Uint8Array,
): Uint8Array {
  requireVariantKeyLength(variantKey);
  const parsed = parseStreamHeader(header);
  const chunkSize = STREAM_CHUNK_SIZE_BASE ** parsed.chunkSizeExponent;
  if (isFinal ? plaintext.length > chunkSize : plaintext.length !== chunkSize) {
    throw new InvalidChunkError(CRYPTO_TEXT.invalidChunk);
  }
  return sodium.aeadEncrypt(plaintext, header, streamNonce(parsed, index, isFinal), variantKey);
}

export function decryptChunk(
  sodium: Sodium,
  variantKey: Uint8Array,
  header: Uint8Array,
  index: number,
  isFinal: boolean,
  ciphertext: Uint8Array,
): Uint8Array {
  requireVariantKeyLength(variantKey);
  const parsed = parseStreamHeader(header);
  const chunkSize = STREAM_CHUNK_SIZE_BASE ** parsed.chunkSizeExponent;
  if (ciphertext.length < AEAD_TAG_BYTES || ciphertext.length > chunkSize + AEAD_TAG_BYTES) {
    throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
  }
  return sodium.aeadDecrypt(ciphertext, header, streamNonce(parsed, index, isFinal), variantKey);
}

function chunkPlaintextSize(layout: StreamLayout, index: number): number {
  return index === layout.chunkCount - 1
    ? layout.plaintextSize - layout.chunkSize * (layout.chunkCount - 1)
    : layout.chunkSize;
}

export function createStreamEncryptor(
  sodium: Sodium,
  variantKey: Uint8Array,
  plaintextSize: number,
): StreamEncryptor {
  const privateHeader = createStreamHeader(sodium);
  const layout = streamLayout(plaintextSize, parseStreamHeader(privateHeader).chunkSizeExponent);
  let nextIndex = 0;
  return {
    header: privateHeader.slice(),
    layout,
    encryptNext: (plaintextChunk) => {
      if (
        nextIndex >= layout.chunkCount ||
        plaintextChunk.length !== chunkPlaintextSize(layout, nextIndex)
      ) {
        throw new InvalidChunkError(CRYPTO_TEXT.invalidChunk);
      }
      const isFinal = nextIndex === layout.chunkCount - 1;
      const ciphertext = encryptChunk(
        sodium,
        variantKey,
        privateHeader,
        nextIndex,
        isFinal,
        plaintextChunk,
      );
      nextIndex += 1;
      return ciphertext;
    },
    isDone: () => nextIndex >= layout.chunkCount,
  };
}

export function createStreamDecryptor(
  sodium: Sodium,
  variantKey: Uint8Array,
  header: Uint8Array,
  expectedPlaintextSize: number,
): StreamDecryptor {
  const privateHeader = header.slice();
  const layout = streamLayout(
    expectedPlaintextSize,
    parseStreamHeader(privateHeader).chunkSizeExponent,
  );
  let nextIndex = 0;
  return {
    layout,
    decryptNext: (ciphertextChunk) => {
      if (nextIndex >= layout.chunkCount) throw new InvalidChunkError(CRYPTO_TEXT.invalidChunk);
      if (ciphertextChunk.length !== chunkPlaintextSize(layout, nextIndex) + AEAD_TAG_BYTES) {
        throw new MalformedObjectError(CRYPTO_TEXT.malformedObject);
      }
      const isFinal = nextIndex === layout.chunkCount - 1;
      const plaintext = decryptChunk(
        sodium,
        variantKey,
        privateHeader,
        nextIndex,
        isFinal,
        ciphertextChunk,
      );
      nextIndex += 1;
      return plaintext;
    },
    finish: () => {
      if (nextIndex < layout.chunkCount)
        throw new TruncatedObjectError(CRYPTO_TEXT.truncatedObject);
    },
  };
}

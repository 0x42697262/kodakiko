import { concatBytes } from '../crypto/bytes.ts';
import { AEAD_TAG_BYTES, KEY_BYTES, STREAM_CHUNK_SIZE_BASE, STREAM_CHUNK_SIZE_EXPONENT, STREAM_HEADER_BYTES } from '../crypto/constants.ts';
import { MalformedObjectError } from '../crypto/errors.ts';
import { createStreamHeader, decryptChunk, encryptChunk } from '../crypto/stream.ts';
import type { Sodium } from '../crypto/sodium.ts';

export type KdkxParams = { readonly salt: Uint8Array; readonly opsLimit: number; readonly memLimitBytes: number };

export type KdkxWriter = {
  readonly header: Uint8Array;
  readonly push: (bytes: Uint8Array) => Uint8Array[];
  readonly finish: () => Uint8Array;
};

const MAGIC = new TextEncoder().encode('KDKX');
const VERSION = 1;
const SALT_BYTES = 16;
const U32_BYTES = 4;
const VERSION_OFFSET = MAGIC.length;
const OPS_OFFSET = VERSION_OFFSET + 1;
const MEM_OFFSET = OPS_OFFSET + U32_BYTES;
const SALT_OFFSET = MEM_OFFSET + U32_BYTES;
const PREFIX_BYTES = SALT_OFFSET + SALT_BYTES;
const MALFORMED = 'Not a Kodakiko encrypted export, or the file is damaged.';

export const KDKX_EXTENSION = '.kdkx';
export const KDKX_CHUNK_BYTES = STREAM_CHUNK_SIZE_BASE ** STREAM_CHUNK_SIZE_EXPONENT;
export const KDKX_SALT_BYTES = SALT_BYTES;

export function isKdkx(bytes: Uint8Array): boolean {
  return bytes.length >= MAGIC.length && MAGIC.every((value, index) => bytes[index] === value);
}

export async function deriveKdkxKey(sodium: Sodium, passphrase: string, params: KdkxParams): Promise<Uint8Array> {
  return sodium.argon2id({
    password: new TextEncoder().encode(passphrase),
    salt: params.salt,
    opsLimit: params.opsLimit,
    memLimitBytes: params.memLimitBytes,
    outputLength: KEY_BYTES,
  });
}

function prefix(params: KdkxParams): Uint8Array {
  const out = new Uint8Array(PREFIX_BYTES);
  out.set(MAGIC, 0);
  out[VERSION_OFFSET] = VERSION;
  const view = new DataView(out.buffer);
  view.setUint32(OPS_OFFSET, params.opsLimit);
  view.setUint32(MEM_OFFSET, params.memLimitBytes);
  out.set(params.salt, SALT_OFFSET);
  return out;
}

export function readKdkxParams(file: Uint8Array): KdkxParams {
  if (!isKdkx(file) || file.length < PREFIX_BYTES + STREAM_HEADER_BYTES + AEAD_TAG_BYTES || file[VERSION_OFFSET] !== VERSION) {
    throw new MalformedObjectError(MALFORMED);
  }
  const view = new DataView(file.buffer, file.byteOffset, file.byteLength);
  return {
    opsLimit: view.getUint32(OPS_OFFSET),
    memLimitBytes: view.getUint32(MEM_OFFSET),
    salt: file.slice(SALT_OFFSET, SALT_OFFSET + SALT_BYTES),
  };
}

export function createKdkxWriter(sodium: Sodium, key: Uint8Array, params: KdkxParams): KdkxWriter {
  const streamHeader = createStreamHeader(sodium);
  let pending: Uint8Array = new Uint8Array(0);
  let index = 0;
  let done = false;
  return {
    header: concatBytes(prefix(params), streamHeader),
    push: (bytes) => {
      if (done) throw new MalformedObjectError(MALFORMED);
      pending = concatBytes(pending, bytes);
      const out: Uint8Array[] = [];
      while (pending.length > KDKX_CHUNK_BYTES) {
        out.push(encryptChunk(sodium, key, streamHeader, index, false, pending.subarray(0, KDKX_CHUNK_BYTES)));
        pending = pending.slice(KDKX_CHUNK_BYTES);
        index += 1;
      }
      return out;
    },
    finish: () => {
      if (done) throw new MalformedObjectError(MALFORMED);
      done = true;
      return encryptChunk(sodium, key, streamHeader, index, true, pending);
    },
  };
}

export function decryptKdkx(sodium: Sodium, key: Uint8Array, file: Uint8Array): Uint8Array {
  readKdkxParams(file);
  const streamHeader = file.slice(PREFIX_BYTES, PREFIX_BYTES + STREAM_HEADER_BYTES);
  const parts: Uint8Array[] = [];
  const fullChunk = KDKX_CHUNK_BYTES + AEAD_TAG_BYTES;
  let offset = PREFIX_BYTES + STREAM_HEADER_BYTES;
  let index = 0;
  while (file.length - offset > fullChunk) {
    parts.push(decryptChunk(sodium, key, streamHeader, index, false, file.subarray(offset, offset + fullChunk)));
    offset += fullChunk;
    index += 1;
  }
  if (file.length - offset < AEAD_TAG_BYTES) throw new MalformedObjectError(MALFORMED);
  parts.push(decryptChunk(sodium, key, streamHeader, index, true, file.subarray(offset)));
  return concatBytes(...parts);
}

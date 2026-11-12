import type { z } from 'zod';
import { concatBytes, utf8Decode, utf8Encode, wipe } from './bytes.ts';
import {
  AD_SEPARATOR,
  AEAD_NONCE_BYTES,
  AEAD_TAG_BYTES,
  BLOB_VERSION_BYTES,
  BLOB_VERSION_OFFSET,
  FORMAT_VERSION_SEALED_BLOB,
  KEY_BYTES,
  PADDING_BLOCK_BYTES,
  type AdContext,
} from './constants.ts';
import { InvalidKeyLengthError, MalformedPayloadError, UnsupportedFormatError } from './errors.ts';
import { padIso7816, unpadIso7816 } from './padding.ts';
import type { Sodium } from './sodium.ts';
import { CRYPTO_TEXT } from './text.ts';

export type PayloadMigrations = ReadonlyMap<
  number,
  (raw: Record<string, unknown>) => Record<string, unknown>
>;

function requireKeyLength(key: Uint8Array): void {
  if (key.length !== KEY_BYTES) throw new InvalidKeyLengthError(CRYPTO_TEXT.invalidKeyLength);
}

export function buildAdditionalData(context: AdContext, bindingId: string): Uint8Array {
  return concatBytes(utf8Encode(context), Uint8Array.of(AD_SEPARATOR), utf8Encode(bindingId));
}

export function sealBlob(
  sodium: Sodium,
  key: Uint8Array,
  plaintext: Uint8Array,
  context: AdContext,
  bindingId: string,
): Uint8Array {
  requireKeyLength(key);
  const nonce = sodium.randomBytes(AEAD_NONCE_BYTES);
  const ciphertext = sodium.aeadEncrypt(
    plaintext,
    buildAdditionalData(context, bindingId),
    nonce,
    key,
  );
  return concatBytes(Uint8Array.of(FORMAT_VERSION_SEALED_BLOB), nonce, ciphertext);
}

export function openBlob(
  sodium: Sodium,
  key: Uint8Array,
  blob: Uint8Array,
  context: AdContext,
  bindingId: string,
): Uint8Array {
  if (blob.length < BLOB_VERSION_BYTES + AEAD_NONCE_BYTES + AEAD_TAG_BYTES)
    throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  if (blob[BLOB_VERSION_OFFSET] !== FORMAT_VERSION_SEALED_BLOB) {
    throw new UnsupportedFormatError(CRYPTO_TEXT.unsupportedFormat);
  }
  const nonce = blob.slice(BLOB_VERSION_BYTES, BLOB_VERSION_BYTES + AEAD_NONCE_BYTES);
  const ciphertext = blob.slice(BLOB_VERSION_BYTES + AEAD_NONCE_BYTES);
  return sodium.aeadDecrypt(ciphertext, buildAdditionalData(context, bindingId), nonce, key);
}

export function wrapKey(
  sodium: Sodium,
  wrappingKey: Uint8Array,
  keyToWrap: Uint8Array,
  context: AdContext,
  bindingId: string,
): Uint8Array {
  requireKeyLength(keyToWrap);
  return sealBlob(sodium, wrappingKey, keyToWrap, context, bindingId);
}

export function unwrapKey(
  sodium: Sodium,
  wrappingKey: Uint8Array,
  wrapped: Uint8Array,
  context: AdContext,
  bindingId: string,
): Uint8Array {
  const key = openBlob(sodium, wrappingKey, wrapped, context, bindingId);
  if (key.length !== KEY_BYTES) {
    wipe(key);
    throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  }
  return key;
}

export function sealJson(
  sodium: Sodium,
  key: Uint8Array,
  value: { readonly schemaVersion: number },
  context: AdContext,
  bindingId: string,
): Uint8Array {
  const padded = padIso7816(utf8Encode(JSON.stringify(value)), PADDING_BLOCK_BYTES);
  return sealBlob(sodium, key, padded, context, bindingId);
}

function parseJsonObject(text: string): Record<string, unknown> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  }
  return parsed as Record<string, unknown>;
}

export function openJson<T>(
  sodium: Sodium,
  key: Uint8Array,
  blob: Uint8Array,
  schema: z.ZodType<T>,
  currentVersion: number,
  migrations: PayloadMigrations,
  context: AdContext,
  bindingId: string,
): T {
  const padded = openBlob(sodium, key, blob, context, bindingId);
  const raw = parseJsonObject(utf8Decode(unpadIso7816(padded, PADDING_BLOCK_BYTES)));
  const schemaVersion = raw['schemaVersion'];
  if (typeof schemaVersion !== 'number' || !Number.isInteger(schemaVersion)) {
    throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  }
  if (schemaVersion > currentVersion) {
    throw new UnsupportedFormatError(CRYPTO_TEXT.unsupportedFormat);
  }
  let migrated = raw;
  for (let version = schemaVersion; version < currentVersion; version += 1) {
    const migrate = migrations.get(version);
    if (migrate === undefined) throw new UnsupportedFormatError(CRYPTO_TEXT.unsupportedFormat);
    migrated = migrate(migrated);
  }
  const result = schema.safeParse(migrated);
  if (!result.success) throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  return result.data;
}

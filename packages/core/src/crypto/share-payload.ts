import { ID_REGEX } from '@kodakiko/contract';
import { bytesEqual, bytesToHex, concatBytes, utf8Encode, wipe } from './bytes.ts';
import {
  FINGERPRINT_BYTES,
  FINGERPRINT_GROUP_CHARS,
  FINGERPRINT_GROUPS,
  FORMAT_VERSION_SHARE_PAYLOAD,
  GENERIC_HASH_BYTES,
  KEY_BYTES,
  SHARE_PAYLOAD_ID_LENGTH_OFFSET,
  SHARE_PAYLOAD_ID_OFFSET,
} from './constants.ts';
import {
  DecryptionError,
  InvalidKeyLengthError,
  MalformedPayloadError,
  UnsupportedFormatError,
} from './errors.ts';
import type { Sodium } from './sodium.ts';
import { CRYPTO_TEXT } from './text.ts';

export function buildSharePayload(collectionId: string, collectionKey: Uint8Array): Uint8Array {
  if (!ID_REGEX.test(collectionId)) throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
  if (collectionKey.length !== KEY_BYTES)
    throw new InvalidKeyLengthError(CRYPTO_TEXT.invalidKeyLength);
  const idBytes = utf8Encode(collectionId);
  return concatBytes(
    Uint8Array.of(FORMAT_VERSION_SHARE_PAYLOAD, idBytes.length),
    idBytes,
    collectionKey,
  );
}

export function openSharePayload(
  sodium: Sodium,
  sealed: Uint8Array,
  publicKey: Uint8Array,
  privateKey: Uint8Array,
  expectedCollectionId: string,
): Uint8Array {
  const payload = sodium.boxSealOpen(sealed, publicKey, privateKey);
  try {
    if (payload[0] !== FORMAT_VERSION_SHARE_PAYLOAD) {
      throw new UnsupportedFormatError(CRYPTO_TEXT.unsupportedFormat);
    }
    const idLength = payload[SHARE_PAYLOAD_ID_LENGTH_OFFSET];
    if (
      idLength === undefined ||
      payload.length !== SHARE_PAYLOAD_ID_OFFSET + idLength + KEY_BYTES
    ) {
      throw new MalformedPayloadError(CRYPTO_TEXT.malformedPayload);
    }
    const embeddedId = payload.slice(SHARE_PAYLOAD_ID_OFFSET, SHARE_PAYLOAD_ID_OFFSET + idLength);
    if (!bytesEqual(embeddedId, utf8Encode(expectedCollectionId))) {
      throw new DecryptionError(CRYPTO_TEXT.shareCollectionMismatch);
    }
    return payload.slice(SHARE_PAYLOAD_ID_OFFSET + idLength);
  } finally {
    wipe(payload);
  }
}

export function publicKeyFingerprint(sodium: Sodium, publicKey: Uint8Array): string {
  const hex = bytesToHex(
    sodium.genericHash(GENERIC_HASH_BYTES, publicKey).slice(0, FINGERPRINT_BYTES),
  );
  const groups: string[] = [];
  for (let group = 0; group < FINGERPRINT_GROUPS; group += 1) {
    const start = group * FINGERPRINT_GROUP_CHARS;
    groups.push(hex.slice(start, start + FINGERPRINT_GROUP_CHARS));
  }
  return groups.join(' ');
}

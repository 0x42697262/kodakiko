import { GENERIC_HASH_BYTES, bytesToHex, utf8Encode, type Sodium } from '@kodakiko/core';
import { ACCOUNT_HASH_HEX_CHARS } from '../constants/storage-constants';

export function hashAccountKey(sodium: Sodium, accountKey: string): string {
  const digest = sodium.genericHash(GENERIC_HASH_BYTES, utf8Encode(accountKey));
  return bytesToHex(digest).slice(0, ACCOUNT_HASH_HEX_CHARS);
}

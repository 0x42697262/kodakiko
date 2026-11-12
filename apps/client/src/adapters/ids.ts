import { encodeBytes } from '@kodakiko/contract';
import type { Sodium } from '@kodakiko/core';
import { RANDOM_ID_BYTES } from '../constants/storage-constants';

export function createRandomId(sodium: Sodium): () => string {
  return () => encodeBytes(sodium.randomBytes(RANDOM_ID_BYTES));
}

import { randomBytes } from 'node:crypto';
import { encodeBytes } from '@kodakiko/contract';
import { ID_BYTES } from '../config/constants.ts';

export function randomToken(bytes: number): string {
  return encodeBytes(randomBytes(bytes));
}

export function randomId(): string {
  return randomToken(ID_BYTES);
}

import { randomBytes } from 'node:crypto';

export function randomMedia(sizeBytes: number): Uint8Array {
  return new Uint8Array(randomBytes(sizeBytes));
}

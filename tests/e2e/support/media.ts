import { randomBytes } from 'node:crypto';

const JPEG_START_OF_SCAN = [0xff, 0xd8, 0xff, 0xda];

export function randomMedia(sizeBytes: number): Uint8Array {
  const bytes = new Uint8Array(randomBytes(sizeBytes));
  bytes.set(JPEG_START_OF_SCAN);
  return bytes;
}

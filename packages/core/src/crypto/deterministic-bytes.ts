import { BYTE_MASK } from './constants.ts';
import type { Sodium } from './sodium.ts';

export type RandomSource = (length: number) => Uint8Array;

export function counterBytes(seed: number, length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  for (let index = 0; index < length; index += 1) {
    bytes[index] = (seed + index) & BYTE_MASK;
  }
  return bytes;
}

export function createCounterRandom(seed: number): RandomSource {
  let drawn = 0;
  return (length) => {
    const bytes = counterBytes(seed + drawn, length);
    drawn += length;
    return bytes;
  };
}

export function withRandomSource(sodium: Sodium, random: RandomSource): Sodium {
  return { ...sodium, randomBytes: random };
}

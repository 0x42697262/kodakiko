import { wipe } from './bytes.ts';
import {
  BYTE_BITS,
  GENERIC_HASH_BYTES,
  KEY_BYTES,
  RECOVERY_CHECKSUM_BITS,
  RECOVERY_WORD_BITS,
  RECOVERY_WORD_COUNT,
} from './constants.ts';
import { InvalidKeyLengthError, InvalidRecoveryWordsError } from './errors.ts';
import type { Sodium } from './sodium.ts';
import { CRYPTO_TEXT } from './text.ts';
import { WORDLIST_EN } from './wordlist-en.ts';

const WORD_INDEX: ReadonlyMap<string, number> = new Map(
  WORDLIST_EN.map((word, index) => [word, index]),
);
const WHITESPACE = /\s+/u;

function checksumByte(sodium: Sodium, recoveryKey: Uint8Array): number {
  return sodium.genericHash(GENERIC_HASH_BYTES, recoveryKey)[0] ?? 0;
}

export function encodeRecoveryKey(sodium: Sodium, recoveryKey: Uint8Array): readonly string[] {
  if (recoveryKey.length !== KEY_BYTES)
    throw new InvalidKeyLengthError(CRYPTO_TEXT.invalidKeyLength);
  const bytes = new Uint8Array(KEY_BYTES + RECOVERY_CHECKSUM_BITS / BYTE_BITS);
  try {
    bytes.set(recoveryKey);
    bytes[KEY_BYTES] = checksumByte(sodium, recoveryKey);
    const words: string[] = [];
    for (let wordPosition = 0; wordPosition < RECOVERY_WORD_COUNT; wordPosition += 1) {
      let wordIndex = 0;
      for (let bit = 0; bit < RECOVERY_WORD_BITS; bit += 1) {
        const bitPosition = wordPosition * RECOVERY_WORD_BITS + bit;
        const byte = bytes[Math.floor(bitPosition / BYTE_BITS)] ?? 0;
        const value = (byte >> (BYTE_BITS - 1 - (bitPosition % BYTE_BITS))) & 1;
        wordIndex = (wordIndex << 1) | value;
      }
      words.push(WORDLIST_EN[wordIndex] ?? '');
    }
    return words;
  } finally {
    wipe(bytes);
  }
}

export function normalizeRecoveryInput(input: string): readonly string[] {
  return input
    .normalize('NFKC')
    .toLowerCase()
    .split(WHITESPACE)
    .filter((part) => part.length > 0);
}

export function decodeRecoveryWords(sodium: Sodium, words: readonly string[]): Uint8Array {
  if (words.length !== RECOVERY_WORD_COUNT) {
    throw new InvalidRecoveryWordsError(null, CRYPTO_TEXT.invalidRecoveryWordCount);
  }
  const bytes = new Uint8Array(KEY_BYTES + RECOVERY_CHECKSUM_BITS / BYTE_BITS);
  try {
    words.forEach((word, wordPosition) => {
      const wordIndex = WORD_INDEX.get(word);
      if (wordIndex === undefined) {
        throw new InvalidRecoveryWordsError(
          wordPosition,
          CRYPTO_TEXT.unknownRecoveryWord(wordPosition + 1),
        );
      }
      for (let bit = 0; bit < RECOVERY_WORD_BITS; bit += 1) {
        const value = (wordIndex >> (RECOVERY_WORD_BITS - 1 - bit)) & 1;
        const bitPosition = wordPosition * RECOVERY_WORD_BITS + bit;
        const byteIndex = Math.floor(bitPosition / BYTE_BITS);
        bytes[byteIndex] =
          (bytes[byteIndex] ?? 0) | (value << (BYTE_BITS - 1 - (bitPosition % BYTE_BITS)));
      }
    });
    const recoveryKey = bytes.slice(0, KEY_BYTES);
    if (bytes[KEY_BYTES] !== checksumByte(sodium, recoveryKey)) {
      wipe(recoveryKey);
      throw new InvalidRecoveryWordsError(null, CRYPTO_TEXT.recoveryChecksumMismatch);
    }
    return recoveryKey;
  } finally {
    wipe(bytes);
  }
}

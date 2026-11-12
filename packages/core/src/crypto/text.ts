export const CRYPTO_TEXT = Object.freeze({
  decryptionFailed: 'The data could not be decrypted.',
  unsupportedFormat: 'The data uses a format version this app does not support.',
  malformedPayload: 'The data is not in the expected format.',
  malformedObject: 'The stored file is not in the expected format.',
  truncatedObject: 'The stored file is incomplete.',
  invalidChunk: 'The chunk has an unexpected size or position.',
  invalidKeyLength: 'The key has an unexpected length.',
  invalidRecoveryWordCount: 'The recovery key must have exactly 24 words.',
  unknownRecoveryWord: (position: number) => `Word ${position} is not in the word list.`,
  recoveryChecksumMismatch: 'The recovery words do not match. Check them and try again.',
  shareCollectionMismatch: 'The shared key belongs to a different collection.',
} as const);

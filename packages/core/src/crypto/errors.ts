export class DecryptionError extends Error {}
export class UnsupportedFormatError extends Error {}
export class MalformedPayloadError extends Error {}
export class MalformedObjectError extends Error {}
export class TruncatedObjectError extends Error {}
export class InvalidChunkError extends Error {}
export class InvalidKeyLengthError extends Error {}
export class InvalidRecoveryWordsError extends Error {
  readonly wordPosition: number | null;
  constructor(wordPosition: number | null, message: string) {
    super(message);
    this.wordPosition = wordPosition;
  }
}

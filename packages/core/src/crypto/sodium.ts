import type { KdfContext } from './constants.ts';

export interface Sodium {
  readonly randomBytes: (length: number) => Uint8Array;
  readonly argon2id: (input: Argon2idInput) => Promise<Uint8Array>;
  readonly kdfDerive: (
    subkeyLength: number,
    subkeyId: number,
    context: KdfContext,
    key: Uint8Array,
  ) => Uint8Array;
  readonly aeadEncrypt: (
    plaintext: Uint8Array,
    additionalData: Uint8Array,
    nonce: Uint8Array,
    key: Uint8Array,
  ) => Uint8Array;
  readonly aeadDecrypt: (
    ciphertext: Uint8Array,
    additionalData: Uint8Array,
    nonce: Uint8Array,
    key: Uint8Array,
  ) => Uint8Array;
  readonly boxKeypair: () => BoxKeypair;
  readonly boxSeal: (message: Uint8Array, recipientPublicKey: Uint8Array) => Uint8Array;
  readonly boxSealOpen: (
    ciphertext: Uint8Array,
    publicKey: Uint8Array,
    privateKey: Uint8Array,
  ) => Uint8Array;
  readonly genericHash: (outputLength: number, message: Uint8Array) => Uint8Array;
}

export type Argon2idInput = {
  readonly password: Uint8Array;
  readonly salt: Uint8Array;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
  readonly outputLength: number;
};

export type BoxKeypair = { readonly publicKey: Uint8Array; readonly privateKey: Uint8Array };

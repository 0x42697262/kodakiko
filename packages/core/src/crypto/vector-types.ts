export type VectorFile<Case> = { readonly format: 1; readonly cases: readonly Case[] };

export type Argon2idCase = {
  readonly password: string;
  readonly salt: string;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
  readonly output: string;
};

export type KdfCase = {
  readonly context: string;
  readonly subkeyId: number;
  readonly key: string;
  readonly subkey: string;
};

export type SealedBlobCase = {
  readonly contextKey: string;
  readonly context: string;
  readonly bindingId: string;
  readonly key: string;
  readonly plaintext: string;
  readonly randomSeed: number;
  readonly blob: string;
};

export type PaddingCase = { readonly input: string; readonly padded: string };

export type StreamCase = {
  readonly plaintextSize: number;
  readonly plaintextSeed: number;
  readonly key: string;
  readonly randomSeed: number;
  readonly header: string;
  readonly chunkCount: number;
  readonly ciphertextSize: number;
  readonly chunkDigests: readonly string[];
  readonly firstChunkPrefix: string;
};

export type RecoveryWordsCase = { readonly recoveryKey: string; readonly words: readonly string[] };

export type SharePayloadCase = {
  readonly collectionId: string;
  readonly collectionKey: string;
  readonly payload: string;
};

export type CryptoVectors = {
  readonly argon2id: VectorFile<Argon2idCase>;
  readonly kdf: VectorFile<KdfCase>;
  readonly sealedBlob: VectorFile<SealedBlobCase>;
  readonly padding: VectorFile<PaddingCase>;
  readonly stream: VectorFile<StreamCase>;
  readonly recoveryWords: VectorFile<RecoveryWordsCase>;
  readonly sharePayload: VectorFile<SharePayloadCase>;
};

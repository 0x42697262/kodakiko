import type {
  Argon2idCase,
  CryptoVectors,
  KdfCase,
  PaddingCase,
  RecoveryWordsCase,
  SealedBlobCase,
  SharePayloadCase,
  StreamCase,
  VectorFile,
} from '../src/crypto/vector-types.ts';
import argon2id from './argon2id.json' with { type: 'json' };
import kdf from './kdf.json' with { type: 'json' };
import padding from './padding.json' with { type: 'json' };
import recoveryWords from './recovery-words.json' with { type: 'json' };
import sealedBlob from './sealed-blob.json' with { type: 'json' };
import sharePayload from './share-payload.json' with { type: 'json' };
import stream from './stream.json' with { type: 'json' };

export const CRYPTO_VECTORS: CryptoVectors = {
  argon2id: argon2id as VectorFile<Argon2idCase>,
  kdf: kdf as VectorFile<KdfCase>,
  sealedBlob: sealedBlob as VectorFile<SealedBlobCase>,
  padding: padding as VectorFile<PaddingCase>,
  stream: stream as VectorFile<StreamCase>,
  recoveryWords: recoveryWords as VectorFile<RecoveryWordsCase>,
  sharePayload: sharePayload as VectorFile<SharePayloadCase>,
};

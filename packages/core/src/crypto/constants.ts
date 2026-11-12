export const KEY_BYTES = 32;
export const AEAD_NONCE_BYTES = 24;
export const AEAD_TAG_BYTES = 16;
export const SALT_BYTES = 16;
export const FORMAT_VERSION_SEALED_BLOB = 0x01;
export const BLOB_VERSION_BYTES = 1;
export const BLOB_VERSION_OFFSET = 0;
export const FORMAT_VERSION_SHARE_PAYLOAD = 0x01;
export const SHARE_PAYLOAD_ID_LENGTH_OFFSET = 1;
export const SHARE_PAYLOAD_ID_OFFSET = 2;
export const STREAM_MAGIC: Readonly<Uint8Array> = Uint8Array.of(0x4b, 0x44, 0x4b, 0x53);
export const STREAM_VERSION = 0x01;
export const STREAM_CHUNK_SIZE_EXPONENT = 20;
export const STREAM_CHUNK_SIZE_EXPONENT_MIN = 10;
export const STREAM_CHUNK_SIZE_EXPONENT_MAX = 24;
export const STREAM_NONCE_PREFIX_BYTES = 16;
export const STREAM_COUNTER_BYTES = 7;
export const STREAM_HEADER_BYTES = 24;
export const STREAM_HEADER_MAGIC_OFFSET = 0;
export const STREAM_HEADER_VERSION_OFFSET = 4;
export const STREAM_HEADER_EXPONENT_OFFSET = 5;
export const STREAM_HEADER_RESERVED_OFFSET = 6;
export const STREAM_HEADER_RESERVED_BYTES = 2;
export const STREAM_HEADER_PREFIX_OFFSET = 8;
export const STREAM_FINAL_FLAG = 0x01;
export const STREAM_NOT_FINAL_FLAG = 0x00;
export const STREAM_MAX_CHUNK_INDEX = Number.MAX_SAFE_INTEGER;
export const STREAM_MIN_OBJECT_BYTES = 40;
export const PADDING_BLOCK_BYTES = 256;
export const PADDING_MARKER = 0x80;
export const BYTE_BITS = 8;
export const BYTE_VALUES = 256;
export const ARGON2ID_MIN_MEM_BYTES = 134_217_728;
export const ARGON2ID_MIN_WORK = 4_294_967_296;
export const ARGON2ID_START_MEM_BYTES = 268_435_456;
export const ARGON2ID_START_OPS = 16;
export const RECOVERY_WORD_COUNT = 24;
export const RECOVERY_WORD_BITS = 11;
export const RECOVERY_CHECKSUM_BITS = 8;
export const RECOVERY_WORDLIST_SIZE = 2048;
export const GENERIC_HASH_BYTES = 32;
export const FINGERPRINT_BYTES = 8;
export const AD_SEPARATOR = 0x00;
export const STREAM_CHUNK_SIZE_BASE = 2;
export const BYTE_MASK = 0xff;
export const HEX_RADIX = 16;
export const HEX_CHARS_PER_BYTE = 2;
export const FINGERPRINT_GROUPS = 4;
export const FINGERPRINT_GROUP_CHARS = 4;
export const VECTOR_FORMAT = 1;

export const KDF_CONTEXTS = Object.freeze({
  login: 'kdklogin',
  recovery: 'kdkrecov',
  media: 'kdkmedia',
  link: 'kdklink_',
} as const);
export type KdfContext = (typeof KDF_CONTEXTS)[keyof typeof KDF_CONTEXTS];

export const KDF_SUBKEY_IDS = Object.freeze({
  login: 1,
  recoveryLogin: 1,
  mediaOriginal: 1,
  mediaThumbnail: 2,
  linkCollectionKey: 1,
  linkAccessToken: 2,
} as const);

export const AD_CONTEXTS = Object.freeze({
  masterKeyByKek: 'kodakiko/v1/wrap/master-key-by-kek',
  masterKeyByRecovery: 'kodakiko/v1/wrap/master-key-by-recovery',
  recoveryKeyByMaster: 'kodakiko/v1/wrap/recovery-key-by-master',
  privateKeyByMaster: 'kodakiko/v1/wrap/private-key-by-master',
  collectionKeyByMaster: 'kodakiko/v1/wrap/collection-key-by-master',
  entryKeyByCollection: 'kodakiko/v1/wrap/entry-key-by-collection',
  mediaKeyByEntry: 'kodakiko/v1/wrap/media-key-by-entry',
  entryMetadata: 'kodakiko/v1/entry-metadata',
  collectionMetadata: 'kodakiko/v1/collection-metadata',
  userSettings: 'kodakiko/v1/user-settings',
  indexSnapshot: 'kodakiko/v1/index-snapshot',
  localKeyByDevice: 'kodakiko/v1/wrap/local-thumbnail-key',
  linkSecretByMaster: 'kodakiko/v1/wrap/link-secret-by-master',
} as const);
export type AdContext = (typeof AD_CONTEXTS)[keyof typeof AD_CONTEXTS];

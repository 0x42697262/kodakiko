import { readFile } from 'node:fs/promises';
import {
  AD_CONTEXTS,
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  KEY_BYTES,
  SNIFF_HEADER_BYTES,
  buildNewEntryMetadata,
  byteSourceFromBytes,
  concatBytes,
  createSignUpMaterial,
  createStreamEncryptor,
  currentTimeZone,
  deviceOffsetMinutes,
  readImageMetadata,
  readVideoMetadata,
  resolveCaptureTime,
  sealJson,
  sniffFormat,
  wrapKey,
  type GeoPoint,
  type MediaFormat,
  type Sodium,
} from '@kodakiko/core';
import {
  EntryMetadataV1,
  MediaDescriptorV1,
  ROUTES,
  encodeBytes,
  presignResponse,
  signUpResponse,
} from '@kodakiko/contract';
import { createNodeSodium } from '../../../packages/core/test/sodium-node.ts';
import {
  BEARER_PREFIX,
  FIXTURES_MEDIA_DIRECTORY,
  HTTP_ACCEPTED,
  SEED_DEVICE_LABEL,
  SEED_HOME_COLLECTION,
  SEED_ID_BYTES,
  SEED_OBJECT_CONTENT_TYPE,
  SEED_PROBE_HEIGHT,
  SEED_PROBE_WIDTH,
  SEED_QUARTER_TURN,
  SEED_REQUEST_TIMEOUT_MS,
  SEED_THREE_QUARTER_TURN,
  SEED_THUMBNAIL_BASE64,
  SEED_THUMBNAIL_MIME,
  SEED_THUMBNAIL_SIZE_PX,
  SEED_VIDEO_DURATION_MS,
  UI_TEST_PASSWORD,
  USERS_ME_DELETE_PATH,
  WEB_ORIGIN,
  type FixtureName,
} from './constants.ts';
import { hostFetch } from './host-fetch.ts';
import { seedInvite, type InviteRole } from './invites.ts';
import { fromRoot } from './paths.ts';
import { STACK_TEXT } from './text.ts';

export { seedInvite, type InviteRole, type SeededInvite } from './invites.ts';

export type SeedAccountOptions = {
  readonly username: string;
  readonly password?: string;
  readonly role?: InviteRole;
};

export type SeededAccount = {
  readonly username: string;
  readonly password: string;
  readonly recoveryWords: readonly string[];
  readonly sessionToken: string;
  readonly masterKey: Uint8Array;
  readonly homeCollectionId: string;
  readonly homeCollectionKey: Uint8Array;
};

export type SeedEntry = {
  readonly takenAtUtc: string;
  readonly offsetMinutes: number;
  readonly note: string;
  readonly tags: readonly string[];
  readonly media: readonly FixtureName[];
};

type ApiOptions = { readonly token?: string; readonly body?: unknown };
type Variant = 'original' | 'thumbnail';
type SeedObject = { readonly id: string; readonly mediaId: string; readonly variant: Variant; readonly ciphertext: Uint8Array };
type Embedded = {
  readonly dateTimeOriginal: string | null;
  readonly offsetTimeOriginal: string | null;
  readonly containerCreationUtc: string | null;
  readonly gps: GeoPoint | null;
  readonly rotationDegrees: number;
};

const HTTP_OK_MIN = 200;
const HTTP_OK_MAX = 299;
const JSON_CONTENT_TYPE = 'application/json';
const FIRST_PART_NUMBER = 1;
const NO_EMBEDDED: Embedded = Object.freeze({
  dateTimeOriginal: null,
  offsetTimeOriginal: null,
  containerCreationUtc: null,
  gps: null,
  rotationDegrees: 0,
});

let sodiumPromise: Promise<Sodium> | null = null;

function sodium(): Promise<Sodium> {
  sodiumPromise ??= createNodeSodium();
  return sodiumPromise;
}

function randomId(lib: Sodium): string {
  return encodeBytes(lib.randomBytes(SEED_ID_BYTES));
}

async function apiOk(method: string, path: string, options: ApiOptions = {}): Promise<unknown> {
  const headers: Record<string, string> = {};
  if (options.token !== undefined) headers['authorization'] = `${BEARER_PREFIX}${options.token}`;
  if (options.body !== undefined) headers['content-type'] = JSON_CONTENT_TYPE;
  const response = await hostFetch(`${WEB_ORIGIN}${path}`, {
    method,
    headers,
    ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
    signal: AbortSignal.timeout(SEED_REQUEST_TIMEOUT_MS),
  });
  const text = await response.text();
  if (response.status < HTTP_OK_MIN || response.status > HTTP_OK_MAX) {
    throw new Error(STACK_TEXT.seedRequestFailed(method, path, response.status, text));
  }
  return text === '' ? null : (JSON.parse(text) as unknown);
}

function encryptWhole(lib: Sodium, key: Uint8Array, plaintext: Uint8Array): Uint8Array {
  const encryptor = createStreamEncryptor(lib, key, plaintext.length);
  const parts = [encryptor.header];
  for (let index = 0; index < encryptor.layout.chunkCount; index += 1) {
    const start = index * encryptor.layout.chunkSize;
    parts.push(encryptor.encryptNext(plaintext.subarray(start, Math.min(start + encryptor.layout.chunkSize, plaintext.length))));
  }
  return concatBytes(...parts);
}

async function putBytes(url: string, bytes: Uint8Array): Promise<void> {
  const response = await hostFetch(url, {
    method: 'PUT',
    headers: { 'content-type': SEED_OBJECT_CONTENT_TYPE },
    body: bytes.slice(),
    signal: AbortSignal.timeout(SEED_REQUEST_TIMEOUT_MS),
  });
  if (!response.ok) throw new Error(STACK_TEXT.seedUploadFailed(response.status));
}

async function uploadObject(account: SeededAccount, object: SeedObject): Promise<void> {
  const presigned = presignResponse.parse(
    await apiOk('POST', ROUTES.objectsPresign(), {
      token: account.sessionToken,
      body: { id: object.id, mediaId: object.mediaId, variant: object.variant, sizeBytes: object.ciphertext.length },
    }),
  );
  if (presigned.mode === 'single') {
    await putBytes(presigned.url, object.ciphertext);
  } else {
    for (const part of presigned.partUrls) {
      const start = (part.partNumber - FIRST_PART_NUMBER) * presigned.partSizeBytes;
      await putBytes(part.url, object.ciphertext.subarray(start, Math.min(start + presigned.partSizeBytes, object.ciphertext.length)));
    }
  }
  await apiOk('POST', ROUTES.objectConfirm(object.id), { token: account.sessionToken });
}

export async function seedAccount(options: SeedAccountOptions): Promise<SeededAccount> {
  const lib = await sodium();
  const password = options.password ?? UI_TEST_PASSWORD;
  const invite = await seedInvite(options.role ?? 'member');
  const material = await createSignUpMaterial(lib, {
    username: options.username,
    password,
    homeCollectionName: SEED_HOME_COLLECTION,
    createdAtUtc: new Date().toISOString(),
    randomId: () => randomId(lib),
  });
  const request = material.request;
  const created = signUpResponse.parse(
    await apiOk('POST', ROUTES.authSignUp(), {
      body: {
        userId: request.userId,
        username: request.username,
        inviteToken: invite.token,
        deviceLabel: SEED_DEVICE_LABEL,
        salt: encodeBytes(request.salt),
        opsLimit: request.opsLimit,
        memLimitBytes: request.memLimitBytes,
        loginKey: encodeBytes(request.loginKey),
        recoveryLoginKey: encodeBytes(request.recoveryLoginKey),
        publicKey: encodeBytes(request.publicKey),
        masterKeyByKek: encodeBytes(request.masterKeyByKek),
        masterKeyByRecovery: encodeBytes(request.masterKeyByRecovery),
        recoveryKeyByMaster: encodeBytes(request.recoveryKeyByMaster),
        privateKeyByMaster: encodeBytes(request.privateKeyByMaster),
        homeCollection: {
          id: request.homeCollection.id,
          keyByOwner: encodeBytes(request.homeCollection.keyByOwner),
          metadataBlob: encodeBytes(request.homeCollection.metadataBlob),
        },
      },
    }),
  );
  return {
    username: options.username,
    password,
    recoveryWords: material.recoveryWords,
    sessionToken: created.session.token,
    masterKey: material.secrets.masterKey,
    homeCollectionId: material.secrets.homeCollectionId,
    homeCollectionKey: material.secrets.homeCollectionKey,
  };
}

export async function seedDeletionRequest(account: SeededAccount): Promise<void> {
  const response = await fetch(`${WEB_ORIGIN}${USERS_ME_DELETE_PATH}`, {
    method: 'POST',
    headers: { Authorization: `${BEARER_PREFIX}${account.sessionToken}` },
  });
  if (response.status !== HTTP_ACCEPTED) {
    throw new Error(STACK_TEXT.deletionRequestFailed(response.status));
  }
}

async function fixtureFile(name: FixtureName): Promise<Uint8Array> {
  try {
    return new Uint8Array(await readFile(fromRoot(FIXTURES_MEDIA_DIRECTORY, name)));
  } catch {
    throw new Error(STACK_TEXT.seedMediaMissing(name));
  }
}

async function readEmbedded(bytes: Uint8Array, format: MediaFormat): Promise<Embedded> {
  const source = byteSourceFromBytes(bytes);
  switch (format.container) {
    case 'jpeg':
    case 'png':
    case 'webp': {
      const image = await readImageMetadata(source, format.container);
      return {
        ...NO_EMBEDDED,
        dateTimeOriginal: image.dateTimeOriginal,
        offsetTimeOriginal: image.offsetTimeOriginal,
        gps: image.gps,
      };
    }
    case 'mp4':
    case 'mov': {
      const video = await readVideoMetadata(source);
      return { ...NO_EMBEDDED, containerCreationUtc: video.creationUtc, gps: video.location, rotationDegrees: video.rotationDegrees };
    }
    default:
      return NO_EMBEDDED;
  }
}

async function seedMedia(lib: Sodium, name: FixtureName, thumbnail: Uint8Array) {
  const bytes = await fixtureFile(name);
  const format = sniffFormat(bytes.subarray(0, SNIFF_HEADER_BYTES));
  if (format === null) throw new Error(STACK_TEXT.seedImportRefused(name, 'unsupported'));
  const embedded = await readEmbedded(bytes, format);
  const now = new Date();
  const time = resolveCaptureTime({
    exifDateTimeOriginal: embedded.dateTimeOriginal,
    exifOffset: embedded.offsetTimeOriginal,
    containerCreationUtc: embedded.containerCreationUtc,
    fileModifiedUtc: null,
    nowUtc: now.toISOString(),
    deviceOffsetMinutes: deviceOffsetMinutes(now),
  });
  const turned = embedded.rotationDegrees === SEED_QUARTER_TURN || embedded.rotationDegrees === SEED_THREE_QUARTER_TURN;
  const mediaId = randomId(lib);
  const mediaKey = lib.randomBytes(KEY_BYTES);
  const originalKey = lib.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaOriginal, KDF_CONTEXTS.media, mediaKey);
  const thumbnailKey = lib.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaThumbnail, KDF_CONTEXTS.media, mediaKey);
  const descriptor = MediaDescriptorV1.parse({
    mediaId,
    kind: format.kind,
    mimeType: format.mimeType,
    width: turned ? SEED_PROBE_HEIGHT : SEED_PROBE_WIDTH,
    height: turned ? SEED_PROBE_WIDTH : SEED_PROBE_HEIGHT,
    durationMs: format.kind === 'video' ? SEED_VIDEO_DURATION_MS : null,
    originalPlaintextBytes: bytes.length,
    thumbnail: {
      width: SEED_THUMBNAIL_SIZE_PX,
      height: SEED_THUMBNAIL_SIZE_PX,
      mimeType: SEED_THUMBNAIL_MIME,
      plaintextBytes: thumbnail.length,
    },
    capturedAtUtc: time.capturedAtUtc,
    originalFileName: name,
  });
  const objects: SeedObject[] = [
    { id: randomId(lib), mediaId, variant: 'original', ciphertext: encryptWhole(lib, originalKey, bytes) },
    { id: randomId(lib), mediaId, variant: 'thumbnail', ciphertext: encryptWhole(lib, thumbnailKey, thumbnail) },
  ];
  return { mediaId, mediaKey, descriptor, time, location: embedded.gps, objects };
}

export async function seedEntries(
  account: SeededAccount,
  entries: readonly SeedEntry[],
): Promise<readonly { entryId: string }[]> {
  const lib = await sodium();
  const thumbnail = new Uint8Array(Buffer.from(SEED_THUMBNAIL_BASE64, 'base64url'));
  const created: { entryId: string }[] = [];
  for (const entry of entries) {
    const media = [];
    for (const name of entry.media) media.push(await seedMedia(lib, name, thumbnail));
    const entryId = randomId(lib);
    const entryKey = lib.randomBytes(KEY_BYTES);
    const nowUtc = new Date().toISOString();
    const metadata = EntryMetadataV1.parse({
      ...buildNewEntryMetadata({
        media: media.map((item) => item.descriptor),
        times: media.map((item) => item.time),
        note: entry.note,
        tags: entry.tags,
        mediaLocations: media.map((item) => item.location),
        deviceLocation: null,
        timeZone: currentTimeZone(),
        nowUtc,
      }),
      takenAtUtc: entry.takenAtUtc,
      utcOffsetMinutes: entry.offsetMinutes,
    });
    const collectionId = account.homeCollectionId;
    await apiOk('POST', ROUTES.entries(), {
      token: account.sessionToken,
      body: {
        id: entryId,
        purpose: 'journal',
        metadataBlob: encodeBytes(sealJson(lib, entryKey, metadata, AD_CONTEXTS.entryMetadata, entryId)),
        memberships: [
          {
            collectionId,
            entryKeyByCollection: encodeBytes(
              wrapKey(lib, account.homeCollectionKey, entryKey, AD_CONTEXTS.entryKeyByCollection, `${collectionId}/${entryId}`),
            ),
          },
        ],
        media: media.map((item) => ({
          mediaId: item.mediaId,
          mediaKeyByEntry: encodeBytes(wrapKey(lib, entryKey, item.mediaKey, AD_CONTEXTS.mediaKeyByEntry, `${entryId}/${item.mediaId}`)),
        })),
      },
    });
    for (const item of media) {
      for (const object of item.objects) await uploadObject(account, object);
    }
    created.push({ entryId });
  }
  return created;
}

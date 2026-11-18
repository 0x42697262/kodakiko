import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import {
  AD_CONTEXTS,
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  concatBytes,
  createSignUpMaterial,
  createStreamEncryptor,
  sealJson,
  wrapKey,
  type Sodium,
} from '@kodakiko/core';
import {
  EntryMetadataV1,
  LINK_TOKEN_HEADER,
  ROUTES,
  encodeBytes,
  presignResponse,
  publicLinkDto,
  signUpResponse,
  type LinkExpiry,
} from '@kodakiko/contract';
import { createNodeSodium } from '../../../packages/core/test/sodium-node.ts';
import {
  BEARER_PREFIX,
  FIXTURES_DIRECTORY,
  LINK_PATH_PREFIX,
  LINK_SEED_DEVICE_LABEL,
  LINK_SEED_HOME_COLLECTION,
  LINK_SEED_ID_BYTES,
  LINK_SEED_KEY_BYTES,
  LINK_SEED_OBJECT_CONTENT_TYPE,
  LINK_SEED_REQUEST_TIMEOUT_MS,
  LINK_SEED_SCHEMA_VERSION,
  LINK_SEED_THUMBNAIL_MIME,
  LINK_SEED_THUMBNAIL_SIZE_PX,
  LINK_SEED_THUMBNAIL_WEBP_BASE64,
  UI_TEST_PASSWORD,
  WEB_ORIGIN,
} from './constants.ts';
import { seedInvite } from './invites.ts';
import { STACK_TEXT } from './text.ts';

const HTTP_OK_MIN = 200;
const HTTP_OK_MAX = 299;
const JSON_CONTENT_TYPE = 'application/json';
const FIRST_PART_NUMBER = 1;

export type LinkMediaSeed = {
  readonly file: string;
  readonly kind: 'photo' | 'video';
  readonly mimeType: string;
  readonly width: number;
  readonly height: number;
  readonly durationMs: number | null;
};

export type LinkEntrySeed = {
  readonly takenAtUtc: string;
  readonly utcOffsetMinutes: number;
  readonly note: string;
  readonly media: readonly LinkMediaSeed[];
};

export type LinkOwner = {
  readonly sessionToken: string;
  readonly masterKey: Uint8Array;
};

export type SeededLink = {
  readonly linkId: string;
  readonly url: string;
  readonly secret: Uint8Array;
  readonly accessToken: string;
};

type ApiOptions = { readonly token?: string; readonly body?: unknown; readonly headers?: Readonly<Record<string, string>> };

let sodiumPromise: Promise<Sodium> | null = null;

function sodium(): Promise<Sodium> {
  sodiumPromise ??= createNodeSodium();
  return sodiumPromise;
}

function randomId(lib: Sodium): string {
  return encodeBytes(lib.randomBytes(LINK_SEED_ID_BYTES));
}

async function api(method: string, path: string, options: ApiOptions = {}): Promise<{ readonly status: number; readonly json: unknown }> {
  const headers: Record<string, string> = { ...options.headers };
  if (options.token !== undefined) headers['authorization'] = `${BEARER_PREFIX}${options.token}`;
  if (options.body !== undefined) headers['content-type'] = JSON_CONTENT_TYPE;
  const response = await fetch(`${WEB_ORIGIN}${path}`, {
    method,
    headers,
    ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
    signal: AbortSignal.timeout(LINK_SEED_REQUEST_TIMEOUT_MS),
  });
  const text = await response.text();
  return { status: response.status, json: text === '' ? null : (JSON.parse(text) as unknown) };
}

async function apiOk(method: string, path: string, options: ApiOptions = {}): Promise<unknown> {
  const result = await api(method, path, options);
  if (result.status < HTTP_OK_MIN || result.status > HTTP_OK_MAX) {
    throw new Error(STACK_TEXT.linkSeedFailed(method, path, result.status, JSON.stringify(result.json)));
  }
  return result.json;
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
  const response = await fetch(url, {
    method: 'PUT',
    headers: { 'content-type': LINK_SEED_OBJECT_CONTENT_TYPE },
    body: bytes.slice(),
    signal: AbortSignal.timeout(LINK_SEED_REQUEST_TIMEOUT_MS),
  });
  if (!response.ok) throw new Error(STACK_TEXT.linkSeedUploadFailed(response.status));
}

async function uploadObject(
  owner: LinkOwner,
  object: { readonly id: string; readonly mediaId: string; readonly variant: 'original' | 'thumbnail'; readonly ciphertext: Uint8Array },
): Promise<void> {
  const presigned = presignResponse.parse(
    await apiOk('POST', ROUTES.objectsPresign(), {
      token: owner.sessionToken,
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
  await apiOk('POST', ROUTES.objectConfirm(object.id), { token: owner.sessionToken });
}

export function thumbnailBytes(): Uint8Array {
  return new Uint8Array(Buffer.from(LINK_SEED_THUMBNAIL_WEBP_BASE64, 'base64'));
}

export async function fixtureBytes(file: string): Promise<Uint8Array> {
  return new Uint8Array(await readFile(join(FIXTURES_DIRECTORY, file)));
}

export function linkUrl(linkId: string, secret: Uint8Array): string {
  return `${WEB_ORIGIN}${LINK_PATH_PREFIX}${linkId}#${encodeBytes(secret)}`;
}

export async function seedLinkOwner(username: string): Promise<LinkOwner> {
  const lib = await sodium();
  const invite = await seedInvite('member');
  const withoutPasswordHashing: Sodium = { ...lib, argon2id: async (input) => lib.randomBytes(input.outputLength) };
  const material = await createSignUpMaterial(withoutPasswordHashing, {
    username,
    password: UI_TEST_PASSWORD,
    homeCollectionName: LINK_SEED_HOME_COLLECTION,
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
        deviceLabel: LINK_SEED_DEVICE_LABEL,
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
  return { sessionToken: created.session.token, masterKey: material.secrets.masterKey };
}

export async function seedPublicLink(
  owner: LinkOwner,
  input: { readonly title: string; readonly expiry: LinkExpiry; readonly entries: readonly LinkEntrySeed[] },
): Promise<SeededLink> {
  const lib = await sodium();
  const now = new Date().toISOString();
  const secret = lib.randomBytes(LINK_SEED_KEY_BYTES);
  const linkKey = lib.kdfDerive(LINK_SEED_KEY_BYTES, KDF_SUBKEY_IDS.linkCollectionKey, KDF_CONTEXTS.link, secret);
  const accessToken = encodeBytes(lib.kdfDerive(LINK_SEED_KEY_BYTES, KDF_SUBKEY_IDS.linkAccessToken, KDF_CONTEXTS.link, secret));
  const collectionId = randomId(lib);
  const collectionMetadata = { schemaVersion: LINK_SEED_SCHEMA_VERSION, name: input.title, createdAtUtc: now };
  await apiOk('POST', ROUTES.collections(), {
    token: owner.sessionToken,
    body: {
      id: collectionId,
      kind: 'link',
      keyByOwner: encodeBytes(wrapKey(lib, owner.masterKey, secret, AD_CONTEXTS.linkSecretByMaster, collectionId)),
      metadataBlob: encodeBytes(sealJson(lib, linkKey, collectionMetadata, AD_CONTEXTS.collectionMetadata, collectionId)),
    },
  });
  const thumbnail = thumbnailBytes();
  for (const entry of input.entries) {
    const entryId = randomId(lib);
    const entryKey = lib.randomBytes(LINK_SEED_KEY_BYTES);
    const descriptors: unknown[] = [];
    const mediaRows: { readonly mediaId: string; readonly mediaKeyByEntry: string }[] = [];
    const objects: { readonly id: string; readonly mediaId: string; readonly variant: 'original' | 'thumbnail'; readonly ciphertext: Uint8Array }[] = [];
    for (const media of entry.media) {
      const original = await fixtureBytes(media.file);
      const mediaId = randomId(lib);
      const mediaKey = lib.randomBytes(LINK_SEED_KEY_BYTES);
      const originalKey = lib.kdfDerive(LINK_SEED_KEY_BYTES, KDF_SUBKEY_IDS.mediaOriginal, KDF_CONTEXTS.media, mediaKey);
      const thumbnailKey = lib.kdfDerive(LINK_SEED_KEY_BYTES, KDF_SUBKEY_IDS.mediaThumbnail, KDF_CONTEXTS.media, mediaKey);
      descriptors.push({
        mediaId,
        kind: media.kind,
        mimeType: media.mimeType,
        width: media.width,
        height: media.height,
        durationMs: media.durationMs,
        originalPlaintextBytes: original.length,
        thumbnail: {
          width: LINK_SEED_THUMBNAIL_SIZE_PX,
          height: LINK_SEED_THUMBNAIL_SIZE_PX,
          mimeType: LINK_SEED_THUMBNAIL_MIME,
          plaintextBytes: thumbnail.length,
        },
        capturedAtUtc: null,
        originalFileName: null,
      });
      mediaRows.push({ mediaId, mediaKeyByEntry: encodeBytes(wrapKey(lib, entryKey, mediaKey, AD_CONTEXTS.mediaKeyByEntry, `${entryId}/${mediaId}`)) });
      objects.push({ id: randomId(lib), mediaId, variant: 'original', ciphertext: encryptWhole(lib, originalKey, original) });
      objects.push({ id: randomId(lib), mediaId, variant: 'thumbnail', ciphertext: encryptWhole(lib, thumbnailKey, thumbnail) });
    }
    const metadata = EntryMetadataV1.parse({
      schemaVersion: LINK_SEED_SCHEMA_VERSION,
      takenAtUtc: entry.takenAtUtc,
      utcOffsetMinutes: entry.utcOffsetMinutes,
      timeZone: null,
      note: entry.note,
      tags: [],
      location: null,
      media: descriptors,
      createdAtUtc: now,
      editedAtUtc: now,
    });
    await apiOk('POST', ROUTES.entries(), {
      token: owner.sessionToken,
      body: {
        id: entryId,
        purpose: 'link_copy',
        metadataBlob: encodeBytes(sealJson(lib, entryKey, metadata, AD_CONTEXTS.entryMetadata, entryId)),
        memberships: [
          {
            collectionId,
            entryKeyByCollection: encodeBytes(wrapKey(lib, linkKey, entryKey, AD_CONTEXTS.entryKeyByCollection, `${collectionId}/${entryId}`)),
          },
        ],
        media: mediaRows,
      },
    });
    for (const object of objects) await uploadObject(owner, object);
  }
  const link = publicLinkDto.parse(
    await apiOk('POST', ROUTES.publicLinks(), { token: owner.sessionToken, body: { collectionId, accessToken, expiry: input.expiry } }),
  );
  return { linkId: link.linkId, url: linkUrl(link.linkId, secret), secret, accessToken };
}

export async function revokePublicLink(owner: LinkOwner, linkId: string): Promise<void> {
  await apiOk('DELETE', ROUTES.publicLink(linkId), { token: owner.sessionToken });
}

export async function openPublicLinkThroughApi(link: SeededLink): Promise<number> {
  const result = await api('POST', ROUTES.publicLinkOpen(link.linkId), { headers: { [LINK_TOKEN_HEADER]: link.accessToken } });
  return result.status;
}

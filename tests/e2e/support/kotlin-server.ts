import { createHash, randomBytes } from 'node:crypto';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { byteSourceFromBytes, readImageMetadata, readVideoMetadata } from '@kodakiko/core';
import { ApiError, createHttpClient, createLinkSession } from '@kodakiko/client/testing';
import { parseLinkFragment } from '../../../apps/client/src/services/links/link-url.ts';
import {
  box,
  buildJpeg,
  buildTiff,
  concat,
  ftyp,
  insertExifIntoJpeg,
  moov,
  mp4Seconds,
  mvhd,
  seededBytes,
  u32be,
  udta,
  xyzBox,
} from '../../../packages/core/test/media/builders.ts';
import { startHarness, type Harness } from './harness.ts';
import {
  BINARY_CONTENT_TYPE,
  BYTES_ENCODING,
  CONTENT_TYPE_HEADER,
  CONTROL_HOST,
  CONTROL_PORT,
  CONTROL_ROUTES,
  ENV_ARGUMENT_INDEX,
  FAILURE_EXIT_CODE,
  HTTP_NO_CONTENT,
  HTTP_NOT_FOUND,
  HTTP_OK,
  HTTP_SERVER_ERROR,
  INVITE_EXPIRES_MS,
  INVITE_ID_BYTES,
  INVITE_PATH,
  INVITE_ROLE,
  INVITE_TOKEN_BYTES,
  INVITE_TOKEN_PREFIX,
  JSON_CONTENT_TYPE,
  MDAT_HEADER_BYTES,
  MP4_EPOCH,
  MVHD_VERSION,
  NO_ENV_ARGUMENT,
  PHOTO_CONTAINER,
  PHOTO_DATE_TIME_ORIGINAL,
  PHOTO_KIND,
  PHOTO_LATITUDE,
  PHOTO_LONGITUDE,
  PHOTO_OFFSET_TIME_ORIGINAL,
  READY_EXIT_CODE,
  STCO_ENTRY_COUNT,
  STCO_VERSION_AND_FLAGS,
  TERMINATION_SIGNALS,
  VIDEO_BRAND,
  VIDEO_COMPATIBLE_BRANDS,
  VIDEO_CREATION_UTC,
  VIDEO_KIND,
  VIDEO_LOCATION_TEXT,
  VIDEO_PAYLOAD_BYTES,
  VIDEO_PAYLOAD_SEED,
} from './kotlin-server-constants.ts';
import {
  findServerEntry,
  pruneTombstonesWithMovedClock,
  rewindEntry,
  serverEntry,
  setQuota,
} from './server-state.ts';

type JsonBody = Record<string, unknown>;
type Handler = (body: JsonBody) => Promise<JsonBody | Uint8Array | null>;

function photoWithGps(): Uint8Array {
  return insertExifIntoJpeg(
    buildJpeg(),
    buildTiff({
      dateTimeOriginal: PHOTO_DATE_TIME_ORIGINAL,
      offsetTimeOriginal: PHOTO_OFFSET_TIME_ORIGINAL,
      gps: { latitude: PHOTO_LATITUDE, longitude: PHOTO_LONGITUDE },
    }),
  );
}

function videoWithLocation(): Uint8Array {
  const payload = seededBytes(VIDEO_PAYLOAD_BYTES, VIDEO_PAYLOAD_SEED);
  const head = ftyp(VIDEO_BRAND, VIDEO_COMPATIBLE_BRANDS);
  const build = (offset: number) =>
    moov(
      mvhd({ version: MVHD_VERSION, creationSeconds1904: mp4Seconds(VIDEO_CREATION_UTC, MP4_EPOCH) }),
      udta(xyzBox(VIDEO_LOCATION_TEXT)),
      box('trak', box('mdia', box('minf', box('stbl', box('stco', concat(STCO_VERSION_AND_FLAGS, u32be(STCO_ENTRY_COUNT), u32be(offset))))))),
    );
  const offset = head.length + build(0).length + MDAT_HEADER_BYTES;
  return concat(head, build(offset), box('mdat', payload));
}

function bytesOf(text: unknown): Buffer {
  return Buffer.from(String(text), BYTES_ENCODING);
}

function textOf(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString(BYTES_ENCODING);
}

function viewer(harness: Harness, url: string) {
  const parsed = new URL(url);
  const secret = parseLinkFragment(parsed.hash);
  if (secret === null) throw new Error('bad link');
  const linkId = parsed.pathname.split('/').at(-1) ?? '';
  const http = createHttpClient({
    baseUrl: async () => harness.baseUrl,
    sessionToken: async () => null,
    onSessionInvalid: () => undefined,
  });
  return createLinkSession({ sodium: harness.sodium, http, linkId, secret, origin: harness.baseUrl });
}

async function openLink(harness: Harness, body: JsonBody): Promise<JsonBody> {
  const session = viewer(harness, String(body['url']));
  try {
    const opened = await session.open();
    if (body['read'] !== true) return { ok: true, title: opened.title, items: [] };
    const items: JsonBody[] = [];
    for (const item of opened.items) {
      const original = byteSourceFromBytes(await session.original(item));
      const thumbnailBytes = (await session.thumbnail(item)).length;
      const kind = item.descriptor.kind;
      const base = { kind, mediaId: item.descriptor.mediaId, note: item.note, thumbnailBytes };
      if (kind === PHOTO_KIND) {
        const meta = await readImageMetadata(original, PHOTO_CONTAINER);
        items.push({ ...base, gps: meta.gps, dateTimeOriginal: meta.dateTimeOriginal });
      } else if (kind === VIDEO_KIND) {
        const meta = await readVideoMetadata(original);
        items.push({ ...base, location: meta.location, creationUtc: meta.creationUtc });
      } else {
        items.push(base);
      }
    }
    return { ok: true, title: opened.title, items };
  } catch (error) {
    if (error instanceof ApiError) return { ok: false, apiError: true, code: error.code };
    return { ok: false, apiError: false, code: error instanceof Error ? error.message : String(error) };
  }
}

async function seedInvite(harness: Harness): Promise<JsonBody> {
  const token = `${INVITE_TOKEN_PREFIX}${randomBytes(INVITE_TOKEN_BYTES).toString(BYTES_ENCODING)}`;
  const now = new Date();
  await harness.db
    .insertInto('invites')
    .values({
      id: randomBytes(INVITE_ID_BYTES).toString(BYTES_ENCODING),
      tokenHash: createHash('sha256').update(token, 'utf8').digest(),
      createdBy: null,
      role: INVITE_ROLE,
      expiresAt: new Date(now.getTime() + INVITE_EXPIRES_MS),
      redeemedBy: null,
      redeemedAt: null,
      revokedAt: null,
      createdAt: now,
    })
    .execute();
  return { token, inviteUrl: `${harness.baseUrl}${INVITE_PATH}${token}` };
}

function handlers(harness: Harness, shutdown: () => void): ReadonlyMap<string, Handler> {
  return new Map<string, Handler>([
    [
      CONTROL_ROUTES.entry,
      async (body) => {
        const entryId = String(body['entryId']);
        const found = await findServerEntry(harness, entryId);
        if (found === undefined) return { entry: null };
        const full = await serverEntry(harness, entryId);
        return {
          entry: {
            revision: full.revision,
            metadataBlob: textOf(full.metadataBlob),
            trashedAt: found.trashedAt === null ? null : new Date(found.trashedAt).toISOString(),
          },
        };
      },
    ],
    [
      CONTROL_ROUTES.quota,
      async (body) => {
        const quota = body['quotaBytes'];
        await setQuota(harness, String(body['userId']), typeof quota === 'number' ? quota : null);
        return null;
      },
    ],
    [
      CONTROL_ROUTES.rewind,
      async (body) => {
        await rewindEntry(harness, String(body['entryId']), String(body['collectionId']), {
          revision: Number(body['revision']),
          metadataBlob: bytesOf(body['metadataBlob']),
        });
        return null;
      },
    ],
    [
      CONTROL_ROUTES.pruneTombstones,
      async () => {
        await pruneTombstonesWithMovedClock(harness);
        return null;
      },
    ],
    [CONTROL_ROUTES.openLink, async (body) => openLink(harness, body)],
    [CONTROL_ROUTES.invite, async () => seedInvite(harness)],
    [CONTROL_ROUTES.photoWithGps, async () => photoWithGps()],
    [CONTROL_ROUTES.videoWithLocation, async () => videoWithLocation()],
    [
      CONTROL_ROUTES.close,
      async () => {
        shutdown();
        return null;
      },
    ],
  ]);
}

async function readBody(request: IncomingMessage): Promise<JsonBody> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk as Uint8Array));
  const text = Buffer.concat(chunks).toString();
  return text.length === 0 ? {} : (JSON.parse(text) as JsonBody);
}

function respond(response: ServerResponse, result: JsonBody | Uint8Array | null): void {
  if (result === null) {
    response.writeHead(HTTP_NO_CONTENT).end();
  } else if (result instanceof Uint8Array) {
    response.writeHead(HTTP_OK, { [CONTENT_TYPE_HEADER]: BINARY_CONTENT_TYPE }).end(Buffer.from(result));
  } else {
    response.writeHead(HTTP_OK, { [CONTENT_TYPE_HEADER]: JSON_CONTENT_TYPE }).end(JSON.stringify(result));
  }
}

async function main(): Promise<void> {
  const env = JSON.parse(process.argv[ENV_ARGUMENT_INDEX] ?? NO_ENV_ARGUMENT) as Record<string, string>;
  const harness = await startHarness({ env });
  let closing: Promise<void> | null = null;
  const shutdown = (): void => {
    closing ??= harness.close().then(
      () => process.exit(READY_EXIT_CODE),
      () => process.exit(FAILURE_EXIT_CODE),
    );
  };
  const routes = handlers(harness, shutdown);
  const control = createServer((request, response) => {
    const handler = routes.get(new URL(request.url ?? '/', harness.baseUrl).pathname);
    if (handler === undefined) {
      response.writeHead(HTTP_NOT_FOUND).end();
      return;
    }
    readBody(request)
      .then(handler)
      .then(
        (result) => respond(response, result),
        (error: unknown) => response.writeHead(HTTP_SERVER_ERROR).end(String(error)),
      );
  });
  control.listen(CONTROL_PORT, CONTROL_HOST, () => {
    const { port } = control.address() as AddressInfo;
    process.stdout.write(`${JSON.stringify({ baseUrl: harness.baseUrl, controlUrl: `http://${CONTROL_HOST}:${port}` })}\n`);
  });
  process.stdin.on('end', shutdown);
  process.stdin.resume();
  for (const signal of TERMINATION_SIGNALS) process.on(signal, shutdown);
}

await main();

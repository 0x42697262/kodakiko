import {
  STREAM_HEADER_BYTES,
  chunkRangeForPlaintextRange,
  decryptChunk,
  parseStreamHeader,
  streamLayout,
  wipe,
  type Sodium,
  type StreamLayout,
} from '@kodakiko/core';
import { HTTP_STATUS, SW_CHUNK_CACHE_SIZE, SW_MAX_RESPONSE_CHUNKS } from '../constants.ts';
import {
  ACCEPT_RANGES_BYTES,
  CONTENT_RANGE_PREFIX,
  HTTP_HEADERS,
  MEDIA_PATH_PREFIX,
  NO_STORE,
  RANGE_PREFIX,
} from '../keys.ts';
import { ciphertextRange, clampRange, parseRange, type ByteRange } from './range.ts';

export type PreparedPlayback = {
  readonly header: Uint8Array;
  readonly layout: StreamLayout;
  readonly chunks: Map<number, Uint8Array>;
  finalChunk: Uint8Array | null;
};

export type Playback = {
  readonly key: Uint8Array;
  readonly objectId: string;
  downloadUrl: string;
  readonly extraHeaders: Readonly<Record<string, string | null>>;
  readonly plaintextSize: number;
  readonly ciphertextSize: number;
  readonly mimeType: string;
  prepared: PreparedPlayback | null;
};

export type MediaWorkerState = { readonly playbacks: Map<string, Playback> };

export type BucketFetch = (
  url: string,
  init: { readonly headers: Readonly<Record<string, string>> },
) => Promise<Response>;

export type MediaHandlerDeps = {
  readonly sodium: Sodium;
  readonly fetch: BucketFetch;
  readonly requestKey: (playbackId: string) => Promise<void>;
  readonly requestUrl: (playbackId: string) => Promise<void>;
};

class BucketError extends Error {}

export function createMediaWorkerState(): MediaWorkerState {
  return { playbacks: new Map() };
}

export function forgetPlayback(state: MediaWorkerState, playbackId: string): void {
  const playback = state.playbacks.get(playbackId);
  if (playback !== undefined) wipe(playback.key);
  state.playbacks.delete(playbackId);
}

export function forgetAllPlaybacks(state: MediaWorkerState): void {
  for (const playbackId of [...state.playbacks.keys()]) forgetPlayback(state, playbackId);
}

export function playbackIdOf(url: string): string | null {
  const path = new URL(url).pathname;
  if (!path.startsWith(MEDIA_PATH_PREFIX)) return null;
  const playbackId = decodeURIComponent(path.slice(MEDIA_PATH_PREFIX.length));
  return playbackId === '' ? null : playbackId;
}

function status(code: number, headers: Record<string, string> = {}): Response {
  return new Response(null, { status: code, headers });
}

function requestHeaders(playback: Playback, range: ByteRange): Record<string, string> {
  const headers: Record<string, string> = {};
  for (const [name, value] of Object.entries(playback.extraHeaders))
    if (value !== null) headers[name] = value;
  headers[HTTP_HEADERS.range] = `${RANGE_PREFIX}${range.start}-${range.end}`;
  return headers;
}

async function fetchRange(
  playbackId: string,
  playback: Playback,
  range: ByteRange,
  deps: MediaHandlerDeps,
): Promise<Uint8Array> {
  for (let attempt = 0; ; attempt += 1) {
    const response = await deps.fetch(playback.downloadUrl, {
      headers: requestHeaders(playback, range),
    });
    if (response.status === HTTP_STATUS.forbidden && attempt === 0) {
      await deps.requestUrl(playbackId);
      continue;
    }
    if (!response.ok) throw new BucketError(String(response.status));
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.length !== range.end - range.start + 1) throw new BucketError(String(bytes.length));
    return bytes;
  }
}

function remember(prepared: PreparedPlayback, index: number, plain: Uint8Array): void {
  prepared.chunks.delete(index);
  prepared.chunks.set(index, plain);
  while (prepared.chunks.size > SW_CHUNK_CACHE_SIZE) {
    const oldest = prepared.chunks.keys().next();
    if (oldest.done === true) break;
    prepared.chunks.delete(oldest.value);
  }
}

async function plainChunk(
  playbackId: string,
  playback: Playback,
  prepared: PreparedPlayback,
  index: number,
  deps: MediaHandlerDeps,
): Promise<Uint8Array> {
  const { layout, header } = prepared;
  const isFinal = index === layout.chunkCount - 1;
  if (isFinal && prepared.finalChunk !== null) return prepared.finalChunk;
  const cached = prepared.chunks.get(index);
  if (cached !== undefined) {
    remember(prepared, index, cached);
    return cached;
  }
  const ciphertext = await fetchRange(
    playbackId,
    playback,
    ciphertextRange(layout, index, index),
    deps,
  );
  const plain = decryptChunk(deps.sodium, playback.key, header, index, isFinal, ciphertext);
  if (isFinal) prepared.finalChunk = plain;
  else remember(prepared, index, plain);
  return plain;
}

async function prepare(
  playbackId: string,
  playback: Playback,
  deps: MediaHandlerDeps,
): Promise<PreparedPlayback> {
  if (playback.prepared !== null) return playback.prepared;
  const header = await fetchRange(
    playbackId,
    playback,
    { start: 0, end: STREAM_HEADER_BYTES - 1 },
    deps,
  );
  const layout = streamLayout(playback.plaintextSize, parseStreamHeader(header).chunkSizeExponent);
  if (layout.ciphertextSize !== playback.ciphertextSize)
    throw new BucketError(String(playback.ciphertextSize));
  const prepared: PreparedPlayback = { header, layout, chunks: new Map(), finalChunk: null };
  await plainChunk(playbackId, playback, prepared, layout.chunkCount - 1, deps);
  playback.prepared = prepared;
  return prepared;
}

async function partialResponse(
  playbackId: string,
  playback: Playback,
  requested: ByteRange,
  deps: MediaHandlerDeps,
): Promise<Response> {
  const prepared = await prepare(playbackId, playback, deps);
  const range = clampRange(requested, prepared.layout, SW_MAX_RESPONSE_CHUNKS);
  const { firstChunk, lastChunk } = chunkRangeForPlaintextRange(
    prepared.layout,
    range.start,
    range.end,
  );
  const { chunkSize } = prepared.layout;
  const slice = (index: number, plain: Uint8Array): Uint8Array => {
    const base = index * chunkSize;
    return plain.subarray(
      Math.max(range.start - base, 0),
      Math.min(range.end - base + 1, plain.length),
    );
  };
  const first = slice(
    firstChunk,
    await plainChunk(playbackId, playback, prepared, firstChunk, deps),
  );
  let next = firstChunk + 1;
  const body = new ReadableStream<Uint8Array>({
    start: (controller) => controller.enqueue(first.slice()),
    pull: async (controller) => {
      if (next > lastChunk) {
        controller.close();
        return;
      }
      const index = next;
      next += 1;
      try {
        controller.enqueue(
          slice(index, await plainChunk(playbackId, playback, prepared, index, deps)).slice(),
        );
      } catch (error) {
        controller.error(error);
      }
    },
  });
  return new Response(body, {
    status: HTTP_STATUS.partialContent,
    headers: {
      [HTTP_HEADERS.contentType]: playback.mimeType,
      [HTTP_HEADERS.contentRange]: `${CONTENT_RANGE_PREFIX}${range.start}-${range.end}/${playback.plaintextSize}`,
      [HTTP_HEADERS.contentLength]: String(range.end - range.start + 1),
      [HTTP_HEADERS.acceptRanges]: ACCEPT_RANGES_BYTES,
      [HTTP_HEADERS.cacheControl]: NO_STORE,
    },
  });
}

export async function handleMediaRequest(
  request: Request,
  state: MediaWorkerState,
  deps: MediaHandlerDeps,
): Promise<Response> {
  const playbackId = playbackIdOf(request.url) ?? '';
  let playback = state.playbacks.get(playbackId);
  if (playback === undefined) {
    await deps.requestKey(playbackId);
    playback = state.playbacks.get(playbackId);
    if (playback === undefined) return status(HTTP_STATUS.gone);
  }
  const requested = parseRange(request.headers.get(HTTP_HEADERS.range), playback.plaintextSize);
  if (requested === null) {
    return status(HTTP_STATUS.rangeNotSatisfiable, {
      [HTTP_HEADERS.contentRange]: `${CONTENT_RANGE_PREFIX}*/${playback.plaintextSize}`,
    });
  }
  try {
    return await partialResponse(playbackId, playback, requested, deps);
  } catch {
    return status(HTTP_STATUS.badGateway);
  }
}

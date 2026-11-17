import { beforeAll, describe, expect, it } from 'vitest';
import { concatBytes, createStreamEncryptor, type Sodium } from '@kodakiko/core';
import { createNodeSodium } from '../../../packages/core/test/sodium-node.ts';
import { createMediaWorkerState, handleMediaRequest, playbackIdOf, type MediaHandlerDeps } from '../src/sw/media-handler.ts';

const MIB = 1_048_576;
const PLAYBACK_ID = 'pb-1';
const MEDIA_URL = `https://app.example/__kdks_media/${PLAYBACK_ID}`;

let sodium: Sodium;
let key: Uint8Array;
let plaintext: Uint8Array;
let object: Uint8Array;

function encrypt(bytes: Uint8Array): Uint8Array {
  const encryptor = createStreamEncryptor(sodium, key, bytes.length);
  const parts = [encryptor.header];
  for (let index = 0; index < encryptor.layout.chunkCount; index += 1) {
    const start = index * encryptor.layout.chunkSize;
    parts.push(encryptor.encryptNext(bytes.subarray(start, Math.min(start + encryptor.layout.chunkSize, bytes.length))));
  }
  return concatBytes(...parts);
}

function bucket(bytes: Uint8Array, expiredUrls: Set<string> = new Set()) {
  const requests: string[] = [];
  const fetchBytes: MediaHandlerDeps['fetch'] = async (url, init) => {
    requests.push(`${url} ${init.headers.Range ?? ""}`);
    if (expiredUrls.has(url)) return new Response(null, { status: 403 });
    const match = /bytes=(\d+)-(\d+)/.exec(init.headers.Range ?? '');
    const start = Number(match?.[1]);
    const end = Number(match?.[2]);
    return new Response(bytes.slice(start, end + 1), { status: 206 });
  };
  return { fetchBytes, requests };
}

function setup(bytes: Uint8Array, plaintextSize: number, expiredUrls?: Set<string>) {
  const state = createMediaWorkerState();
  state.playbacks.set(PLAYBACK_ID, {
    key: key.slice(),
    objectId: 'obj',
    downloadUrl: 'https://bucket.example/obj?v=1',
    extraHeaders: {},
    plaintextSize,
    ciphertextSize: bytes.length,
    mimeType: 'video/mp4',
    prepared: null,
  });
  const { fetchBytes, requests } = bucket(bytes, expiredUrls);
  const signals: string[] = [];
  const deps: MediaHandlerDeps = {
    sodium,
    fetch: fetchBytes,
    requestKey: async (playbackId) => {
      signals.push(`needKey ${playbackId}`);
    },
    requestUrl: async (playbackId) => {
      signals.push(`needUrl ${playbackId}`);
      const playback = state.playbacks.get(playbackId);
      if (playback !== undefined) playback.downloadUrl = 'https://bucket.example/obj?v=2';
    },
  };
  return { state, deps, requests, signals };
}

async function body(response: Response): Promise<Uint8Array> {
  return new Uint8Array(await response.arrayBuffer());
}

function rangeRequest(range: string | null): Request {
  return new Request(MEDIA_URL, range === null ? {} : { headers: { Range: range } });
}

beforeAll(async () => {
  sodium = await createNodeSodium();
  key = sodium.randomBytes(32);
  plaintext = new Uint8Array(3 * MIB + 1000);
  for (let index = 0; index < plaintext.length; index += 1) plaintext[index] = (index * 7 + 3) % 251;
  object = encrypt(plaintext);
});

describe('handleMediaRequest', () => {
  it('serves bytes=0- as a 206 with the whole (clamped) plaintext', async () => {
    const { state, deps } = setup(object, plaintext.length);
    const response = await handleMediaRequest(rangeRequest('bytes=0-'), state, deps);
    expect(response.status).toBe(206);
    expect(response.headers.get('Content-Range')).toBe(`bytes 0-${plaintext.length - 1}/${plaintext.length}`);
    expect(response.headers.get('Content-Type')).toBe('video/mp4');
    expect(response.headers.get('Accept-Ranges')).toBe('bytes');
    expect(response.headers.get('Cache-Control')).toBe('no-store');
    expect(await body(response)).toEqual(plaintext);
  });

  it('serves a range that crosses a chunk boundary', async () => {
    const { state, deps } = setup(object, plaintext.length);
    const response = await handleMediaRequest(rangeRequest('bytes=1048570-1048590'), state, deps);
    expect(response.headers.get('Content-Length')).toBe('21');
    expect(await body(response)).toEqual(plaintext.slice(1048570, 1048591));
  });

  it('serves the last byte', async () => {
    const { state, deps } = setup(object, plaintext.length);
    const last = plaintext.length - 1;
    const response = await handleMediaRequest(rangeRequest(`bytes=${last}-`), state, deps);
    expect(await body(response)).toEqual(plaintext.slice(last));
  });

  it('answers 416 for an unsatisfiable range', async () => {
    const { state, deps } = setup(object, plaintext.length);
    const response = await handleMediaRequest(rangeRequest(`bytes=${plaintext.length}-`), state, deps);
    expect(response.status).toBe(416);
    expect(response.headers.get('Content-Range')).toBe(`bytes */${plaintext.length}`);
  });

  it('answers 502 for a truncated object (last chunk removed)', async () => {
    const truncated = object.slice(0, 24 + 3 * (MIB + 16));
    const { state, deps } = setup(truncated, 3 * MIB);
    const response = await handleMediaRequest(rangeRequest('bytes=0-10'), state, deps);
    expect(response.status).toBe(502);
  });

  it('asks the page for a fresh URL after a 403 and then succeeds', async () => {
    const { state, deps, signals } = setup(object, plaintext.length, new Set(['https://bucket.example/obj?v=1']));
    const response = await handleMediaRequest(rangeRequest('bytes=5-9'), state, deps);
    expect(signals).toEqual([`needUrl ${PLAYBACK_ID}`]);
    expect(response.status).toBe(206);
    expect(await body(response)).toEqual(plaintext.slice(5, 10));
  });

  it('asks for the key when the playback is unknown and answers 410 if it never comes', async () => {
    const { state, deps, signals } = setup(object, plaintext.length);
    state.playbacks.clear();
    const response = await handleMediaRequest(rangeRequest('bytes=0-'), state, deps);
    expect(signals).toEqual([`needKey ${PLAYBACK_ID}`]);
    expect(response.status).toBe(410);
  });

  it('fetches only the chunks it needs from the bucket, one chunk per request', async () => {
    const { state, deps, requests } = setup(object, plaintext.length);
    await body(await handleMediaRequest(rangeRequest('bytes=1048570-1048590'), state, deps));
    const step = MIB + 16;
    expect(requests.slice(2)).toEqual([
      `https://bucket.example/obj?v=1 bytes=24-${24 + step - 1}`,
      `https://bucket.example/obj?v=1 bytes=${24 + step}-${24 + 2 * step - 1}`,
    ]);
  });

  it('serves overlapping ranges from the chunk cache without fetching again', async () => {
    const { state, deps, requests } = setup(object, plaintext.length);
    await body(await handleMediaRequest(rangeRequest('bytes=0-'), state, deps));
    const before = requests.length;
    await body(await handleMediaRequest(rangeRequest(`bytes=${MIB}-`), state, deps));
    expect(requests.length).toBe(before);
  });
});

describe('link viewer additions', () => {
  it('sends the playback extra headers (proxied link objects) with every range request', async () => {
    const { state, deps } = setup(object, plaintext.length);
    const seen: Record<string, string>[] = [];
    const inner = deps.fetch;
    const playback = state.playbacks.get(PLAYBACK_ID);
    if (playback === undefined) throw new Error('no playback');
    state.playbacks.set(PLAYBACK_ID, {
      ...playback,
      extraHeaders: { 'X-Kodakiko-Link-Token': 'link-token', 'X-Kodakiko-View-Token': 'view-token', 'X-Dropped': null },
    });
    const response = await handleMediaRequest(rangeRequest('bytes=0-9'), state, {
      ...deps,
      fetch: async (url, init) => {
        seen.push({ ...init.headers });
        return inner(url, init);
      },
    });
    expect(await body(response)).toEqual(plaintext.slice(0, 10));
    expect(seen.length).toBeGreaterThan(0);
    for (const headers of seen) {
      expect(headers['X-Kodakiko-Link-Token']).toBe('link-token');
      expect(headers['X-Kodakiko-View-Token']).toBe('view-token');
      expect('X-Dropped' in headers).toBe(false);
    }
  });

  it('only handles requests under the media stream path', () => {
    expect(playbackIdOf(MEDIA_URL)).toBe(PLAYBACK_ID);
    expect(playbackIdOf('https://app.example/s/abc')).toBeNull();
    expect(playbackIdOf('https://app.example/media-sw.js')).toBeNull();
  });
});

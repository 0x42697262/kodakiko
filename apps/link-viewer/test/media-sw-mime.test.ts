import { beforeAll, describe, expect, it } from 'vitest';
import { createStreamEncryptor, concatBytes, type Sodium } from '@kodakiko/core';
import { createNodeSodium } from '../../../packages/core/test/sodium-node.ts';
import { createMediaWorkerState, handleMediaRequest, type MediaHandlerDeps } from '../src/sw/media-handler.ts';

const PLAYBACK_ID = 'pb-mime';
const MEDIA_URL = `https://app.example/__kdks_media/${PLAYBACK_ID}`;

let sodium: Sodium;
let key: Uint8Array;
const plaintext = new Uint8Array([9, 8, 7, 6, 5, 4, 3, 2, 1]);
let object: Uint8Array;

beforeAll(async () => {
  sodium = await createNodeSodium();
  key = sodium.randomBytes(32);
  const encryptor = createStreamEncryptor(sodium, key, plaintext.length);
  object = concatBytes(encryptor.header, encryptor.encryptNext(plaintext));
});

async function contentType(mimeType: string): Promise<string | null> {
  const state = createMediaWorkerState();
  state.playbacks.set(PLAYBACK_ID, {
    key: key.slice(),
    objectId: 'obj',
    downloadUrl: 'https://bucket.example/obj',
    extraHeaders: {},
    plaintextSize: plaintext.length,
    ciphertextSize: object.length,
    mimeType,
    prepared: null,
  });
  const deps: MediaHandlerDeps = {
    sodium,
    fetch: async (_url, init) => {
      const match = /bytes=(\d+)-(\d+)/.exec(init.headers.Range ?? '');
      return new Response(object.slice(Number(match?.[1]), Number(match?.[2]) + 1), { status: 206 });
    },
    requestKey: async () => undefined,
    requestUrl: async () => undefined,
  };
  const response = await handleMediaRequest(new Request(MEDIA_URL, { headers: { Range: 'bytes=0-' } }), state, deps);
  return response.headers.get('Content-Type');
}

describe('media worker content type allowlist', () => {
  it('serves supported video types unchanged', async () => {
    expect(await contentType('video/webm')).toBe('video/webm');
    expect(await contentType('video/quicktime')).toBe('video/quicktime');
  });

  it('serves an unsupported metadata type as application/octet-stream', async () => {
    expect(await contentType('text/html')).toBe('application/octet-stream');
    expect(await contentType('image/svg+xml')).toBe('application/octet-stream');
  });
});

import { createServer, type IncomingMessage, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ANDROID_HOST_ALIAS } from './constants.ts';
import { hostFetch } from './host-fetch.ts';

type Seen = { method: string; host: string; url: string; body: string; contentType: string };

let server: Server;
let port = 0;
let seen: Seen[] = [];

function readBody(request: IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    request.on('data', (chunk: Buffer) => chunks.push(chunk));
    request.on('end', () => resolve(Buffer.concat(chunks).toString()));
  });
}

beforeEach(async () => {
  seen = [];
  server = createServer((request, response) => {
    void readBody(request).then((body) => {
      seen.push({
        method: request.method ?? '',
        host: request.headers.host ?? '',
        url: request.url ?? '',
        body,
        contentType: request.headers['content-type'] ?? '',
      });
      if (request.url === '/empty') {
        response.writeHead(204).end();
        return;
      }
      response.writeHead(201, { etag: '"abc"' }).end('stored');
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  port = (server.address() as AddressInfo).port;
});

afterEach(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe('hostFetch', () => {
  it('sends a request for the emulator host alias to the loopback address with the original Host', async () => {
    const response = await hostFetch(`http://${ANDROID_HOST_ALIAS}:${port}/bucket/key?X-Amz-Signature=s`, {
      method: 'PUT',
      headers: { 'content-type': 'application/octet-stream' },
      body: new Uint8Array([104, 105]),
    });
    expect(response.status).toBe(201);
    expect(response.headers.get('etag')).toBe('"abc"');
    expect(await response.text()).toBe('stored');
    expect(seen).toEqual([
      {
        method: 'PUT',
        host: `${ANDROID_HOST_ALIAS}:${port}`,
        url: '/bucket/key?X-Amz-Signature=s',
        body: 'hi',
        contentType: 'application/octet-stream',
      },
    ]);
  });

  it('returns a response without a body for 204', async () => {
    const response = await hostFetch(new URL(`http://${ANDROID_HOST_ALIAS}:${port}/empty`));
    expect(response.status).toBe(204);
    expect(response.body).toBeNull();
  });

  it('leaves other URLs to the regular fetch', async () => {
    const response = await hostFetch(`http://127.0.0.1:${port}/plain`);
    expect(await response.text()).toBe('stored');
    expect(seen[0]?.host).toBe(`127.0.0.1:${port}`);
  });
});

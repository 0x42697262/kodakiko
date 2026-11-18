import { request } from 'node:http';
import {
  ANDROID_HOST_ALIAS,
  HOST_LOOPBACK_ADDRESS,
  HTTP_BAD_GATEWAY,
  HTTP_CONTENT_LENGTH_HEADER,
  HTTP_DEFAULT_METHOD,
  HTTP_DEFAULT_PORT,
  HTTP_HEADER_LIST_SEPARATOR,
  HTTP_HOST_HEADER,
  HTTP_NULL_BODY_STATUSES,
} from './constants.ts';

function requestUrl(input: RequestInfo | URL): URL {
  if (input instanceof URL) return input;
  return new URL(typeof input === 'string' ? input : input.url);
}

async function bodyBytes(init: RequestInit | undefined): Promise<Buffer | undefined> {
  if (init?.body === undefined || init.body === null) return undefined;
  return Buffer.from(await new Response(init.body).arrayBuffer());
}

async function loopbackFetch(url: URL, init: RequestInit | undefined): Promise<Response> {
  const body = await bodyBytes(init);
  const headers: Record<string, string> = {};
  new Headers(init?.headers).forEach((value, name) => {
    headers[name] = value;
  });
  headers[HTTP_HOST_HEADER] = url.host;
  if (body !== undefined) headers[HTTP_CONTENT_LENGTH_HEADER] = String(body.length);
  return new Promise((resolve, reject) => {
    const outgoing = request(
      {
        host: HOST_LOOPBACK_ADDRESS,
        port: url.port === '' ? HTTP_DEFAULT_PORT : Number(url.port),
        path: `${url.pathname}${url.search}`,
        method: init?.method ?? HTTP_DEFAULT_METHOD,
        headers,
        signal: init?.signal ?? undefined,
      },
      (incoming) => {
        const chunks: Buffer[] = [];
        incoming.on('data', (chunk: Buffer) => chunks.push(chunk));
        incoming.on('error', reject);
        incoming.on('end', () => {
          const responseHeaders = new Headers();
          for (const [name, value] of Object.entries(incoming.headers)) {
            if (value === undefined) continue;
            responseHeaders.set(name, Array.isArray(value) ? value.join(HTTP_HEADER_LIST_SEPARATOR) : value);
          }
          const status = incoming.statusCode ?? HTTP_BAD_GATEWAY;
          const content = HTTP_NULL_BODY_STATUSES.includes(status) ? null : Buffer.concat(chunks);
          resolve(new Response(content, { status, headers: responseHeaders }));
        });
      },
    );
    outgoing.on('error', reject);
    if (body !== undefined) outgoing.write(body);
    outgoing.end();
  });
}

export const hostFetch: typeof fetch = (input, init) => {
  const url = requestUrl(input);
  if (url.hostname === ANDROID_HOST_ALIAS) return loopbackFetch(url, init);
  return fetch(input, init);
};

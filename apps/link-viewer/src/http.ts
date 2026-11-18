import {
  LINK_TOKEN_HEADER,
  LINK_VIEW_TOKEN_HEADER,
  errorResponseSchema,
  type ErrorCode,
} from '@kodakiko/contract';
import type { z } from 'zod';
import {
  FETCH_REDIRECT_POLICY,
  HTTP_BACKOFF_FACTOR,
  HTTP_RETRY_AFTER_MAX_MS,
  HTTP_RETRY_ATTEMPTS,
  HTTP_RETRY_BASE_MS,
  HTTP_RETRY_CAP_MS,
  HTTP_STATUS,
  HTTP_TIMEOUT_MS,
  MS_PER_SECOND,
  OPAQUE_REDIRECT_RESPONSE,
} from './constants.ts';
import { HTTP_HEADERS, JSON_CONTENT_TYPE } from './keys.ts';
import { TEXT } from './text.ts';

export type LinkAuth = { readonly linkToken: string; readonly viewToken?: string | null };

export type ApiCall<Response> = {
  readonly method: 'GET' | 'POST';
  readonly path: string;
  readonly body?: unknown;
  readonly responseSchema: z.ZodType<Response>;
  readonly auth: LinkAuth;
  readonly idempotent: boolean;
  readonly timeoutMs?: number;
};

export type HttpClient = {
  readonly request: <Response>(call: ApiCall<Response>) => Promise<Response>;
};

export type HttpClientDeps = {
  readonly baseUrl: string;
  readonly fetcher?: typeof fetch;
  readonly sleep?: (milliseconds: number) => Promise<void>;
  readonly random?: () => number;
};

export class ApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode;
  readonly details: unknown;
  readonly requestId: string | null;
  constructor(status: number, code: ErrorCode, details: unknown, requestId: string | null) {
    super(TEXT.apiError(code));
    this.status = status;
    this.code = code;
    this.details = details;
    this.requestId = requestId;
  }
}

export class NetworkError extends Error {
  constructor() {
    super(TEXT.networkError);
  }
}

type RawResponse = { readonly status: number; readonly headers: Headers; readonly text: string };
type ParsedJson = { readonly ok: true; readonly value: unknown } | { readonly ok: false };

function isSuccess(status: number): boolean {
  return status >= HTTP_STATUS.ok && status < HTTP_STATUS.multipleChoices;
}

function isRedirect(response: Response): boolean {
  return (
    response.redirected ||
    response.type === OPAQUE_REDIRECT_RESPONSE ||
    (response.status >= HTTP_STATUS.multipleChoices && response.status < HTTP_STATUS.clientErrorMin)
  );
}

function isRetryableStatus(status: number): boolean {
  return status === HTTP_STATUS.tooManyRequests || status >= HTTP_STATUS.serverErrorMin;
}

function parseJson(text: string): ParsedJson {
  if (text === '') return { ok: true, value: undefined };
  try {
    return { ok: true, value: JSON.parse(text) as unknown };
  } catch {
    return { ok: false };
  }
}

function defaultSleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function buildHeaders(call: ApiCall<unknown>): Headers {
  const headers = new Headers();
  if (call.body !== undefined) headers.set(HTTP_HEADERS.contentType, JSON_CONTENT_TYPE);
  headers.set(LINK_TOKEN_HEADER, call.auth.linkToken);
  if (call.auth.viewToken !== undefined && call.auth.viewToken !== null)
    headers.set(LINK_VIEW_TOKEN_HEADER, call.auth.viewToken);
  return headers;
}

function failure(raw: RawResponse): ApiError {
  const body = parseJson(raw.text);
  const parsed = body.ok ? errorResponseSchema.safeParse(body.value) : null;
  if (parsed?.success !== true)
    return new ApiError(raw.status, 'INTERNAL', null, raw.headers.get(HTTP_HEADERS.requestId));
  const { code, details, requestId } = parsed.data.error;
  return new ApiError(raw.status, code, details, requestId);
}

function success<Response>(raw: RawResponse, schema: z.ZodType<Response>): Response {
  const body = parseJson(raw.text);
  const parsed = body.ok ? schema.safeParse(body.value) : null;
  if (parsed?.success === true) return parsed.data;
  throw new ApiError(raw.status, 'INTERNAL', null, raw.headers.get(HTTP_HEADERS.requestId));
}

export function createHttpClient(deps: HttpClientDeps): HttpClient {
  const fetcher =
    deps.fetcher ?? ((input: RequestInfo | URL, init?: RequestInit) => fetch(input, init));
  const sleep = deps.sleep ?? defaultSleep;
  const random = deps.random ?? Math.random;

  async function send(
    url: string,
    init: RequestInit,
    timeoutMs: number,
  ): Promise<RawResponse | null> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetcher(url, { ...init, signal: controller.signal });
      if (isRedirect(response)) return null;
      return { status: response.status, headers: response.headers, text: await response.text() };
    } catch {
      return null;
    } finally {
      clearTimeout(timer);
    }
  }

  function backoffDelay(retryIndex: number): number {
    return Math.floor(
      random() *
        Math.min(HTTP_RETRY_CAP_MS, HTTP_RETRY_BASE_MS * HTTP_BACKOFF_FACTOR ** retryIndex),
    );
  }

  function retryDelay(raw: RawResponse, retryIndex: number): number {
    const header = raw.headers.get(HTTP_HEADERS.retryAfter);
    const seconds = header === null || header.trim() === '' ? Number.NaN : Number(header);
    if (raw.status === HTTP_STATUS.tooManyRequests && Number.isFinite(seconds) && seconds >= 0) {
      return Math.min(seconds * MS_PER_SECOND, HTTP_RETRY_AFTER_MAX_MS);
    }
    return backoffDelay(retryIndex);
  }

  async function request<Response>(call: ApiCall<Response>): Promise<Response> {
    const url = `${deps.baseUrl}${call.path}`;
    const init: RequestInit = {
      method: call.method,
      headers: buildHeaders(call),
      credentials: 'omit',
      redirect: FETCH_REDIRECT_POLICY,
      ...(call.body === undefined ? {} : { body: JSON.stringify(call.body) }),
    };
    const timeoutMs = call.timeoutMs ?? HTTP_TIMEOUT_MS;
    for (let attempt = 0; ; attempt += 1) {
      const raw = await send(url, init, timeoutMs);
      const canRetry = call.idempotent && attempt + 1 < HTTP_RETRY_ATTEMPTS;
      if (raw === null) {
        if (!canRetry) throw new NetworkError();
        await sleep(backoffDelay(attempt));
      } else if (isRetryableStatus(raw.status) && canRetry) {
        await sleep(retryDelay(raw, attempt));
      } else if (isSuccess(raw.status)) {
        return success(raw, call.responseSchema);
      } else {
        throw failure(raw);
      }
    }
  }

  return { request };
}

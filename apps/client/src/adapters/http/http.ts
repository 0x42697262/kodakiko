import { errorResponseSchema, type ErrorCode } from '@kodakiko/contract';
import type { z } from 'zod';
import {
  HTTP_BACKOFF_FACTOR,
  HTTP_RETRY_AFTER_MAX_MS,
  HTTP_RETRY_ATTEMPTS,
  HTTP_RETRY_BASE_MS,
  HTTP_RETRY_CAP_MS,
  HTTP_STATUS,
  HTTP_TIMEOUT_MS,
  MS_PER_SECOND,
} from '../../constants/http-constants';
import { BEARER_PREFIX, HTTP_HEADERS, JSON_CONTENT_TYPE } from '../../keys/http-keys';
import { EN } from '../../text/en';

export type ApiCall<Response> = {
  readonly method: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  readonly path: string;
  readonly body?: unknown;
  readonly responseSchema: z.ZodType<Response>;
  readonly auth: 'session' | 'none' | { readonly linkToken: string };
  readonly idempotent: boolean;
  readonly timeoutMs?: number;
};

export type HttpClient = {
  readonly request: <Response>(call: ApiCall<Response>) => Promise<Response>;
};

export type HttpClientDeps = {
  readonly baseUrl: () => Promise<string>;
  readonly sessionToken: () => Promise<string | null>;
  readonly onSessionInvalid: () => void;
  readonly fetcher?: typeof fetch;
  readonly sleep?: (ms: number) => Promise<void>;
  readonly random?: () => number;
};

export class ApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode;
  readonly details: unknown;
  readonly requestId: string | null;
  constructor(status: number, code: ErrorCode, details: unknown, requestId: string | null) {
    super(EN['http.apiError'](code));
    this.status = status;
    this.code = code;
    this.details = details;
    this.requestId = requestId;
  }
}

export class NetworkError extends Error {
  constructor() {
    super(EN['http.networkError']);
  }
}

type RawResponse = {
  readonly status: number;
  readonly headers: Headers;
  readonly text: string;
};

const SESSION_INVALID_CODES: readonly ErrorCode[] = ['UNAUTHENTICATED', 'SESSION_EXPIRED'];

function isDevelopmentBuild(): boolean {
  return typeof __DEV__ !== 'undefined' && __DEV__;
}

function isSuccess(status: number): boolean {
  return status >= HTTP_STATUS.ok && status < HTTP_STATUS.multipleChoices;
}

function isRetryableStatus(status: number): boolean {
  return status === HTTP_STATUS.tooManyRequests || status >= HTTP_STATUS.serverErrorMin;
}

function parseJson(
  text: string,
): { readonly ok: true; readonly value: unknown } | { readonly ok: false } {
  if (text === '') return { ok: true, value: undefined };
  try {
    return { ok: true, value: JSON.parse(text) as unknown };
  } catch {
    return { ok: false };
  }
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function createHttpClient(deps: HttpClientDeps): HttpClient {
  const fetcher = deps.fetcher ?? ((input, init) => fetch(input, init));
  const sleep = deps.sleep ?? defaultSleep;
  const random = deps.random ?? Math.random;

  async function buildHeaders(call: ApiCall<unknown>): Promise<Headers> {
    const headers = new Headers();
    if (call.body !== undefined) headers.set(HTTP_HEADERS.contentType, JSON_CONTENT_TYPE);
    if (call.auth === 'session') {
      const token = await deps.sessionToken();
      if (token !== null) headers.set(HTTP_HEADERS.authorization, `${BEARER_PREFIX}${token}`);
    } else if (call.auth !== 'none') {
      headers.set(HTTP_HEADERS.linkToken, call.auth.linkToken);
    }
    return headers;
  }

  async function send(
    url: string,
    init: RequestInit,
    timeoutMs: number,
  ): Promise<RawResponse | null> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetcher(url, { ...init, signal: controller.signal });
      return { status: response.status, headers: response.headers, text: await response.text() };
    } catch {
      return null;
    } finally {
      clearTimeout(timer);
    }
  }

  function backoffDelay(retryIndex: number): number {
    const ceiling = Math.min(
      HTTP_RETRY_CAP_MS,
      HTTP_RETRY_BASE_MS * HTTP_BACKOFF_FACTOR ** retryIndex,
    );
    return Math.floor(random() * ceiling);
  }

  function retryDelay(raw: RawResponse, retryIndex: number): number {
    if (raw.status === HTTP_STATUS.tooManyRequests) {
      const seconds = Number(raw.headers.get(HTTP_HEADERS.retryAfter));
      if (raw.headers.has(HTTP_HEADERS.retryAfter) && Number.isFinite(seconds) && seconds >= 0) {
        return Math.min(seconds * MS_PER_SECOND, HTTP_RETRY_AFTER_MAX_MS);
      }
    }
    return backoffDelay(retryIndex);
  }

  function failure(raw: RawResponse): ApiError {
    const parsedBody = parseJson(raw.text);
    const parsedError = parsedBody.ok ? errorResponseSchema.safeParse(parsedBody.value) : null;
    if (parsedError?.success !== true) {
      return new ApiError(raw.status, 'INTERNAL', null, raw.headers.get(HTTP_HEADERS.requestId));
    }
    const { code, details, requestId } = parsedError.data.error;
    if (raw.status === HTTP_STATUS.unauthorized && SESSION_INVALID_CODES.includes(code)) {
      deps.onSessionInvalid();
    }
    return new ApiError(raw.status, code, details, requestId);
  }

  function success<Response>(raw: RawResponse, schema: z.ZodType<Response>): Response {
    const parsedBody = parseJson(raw.text);
    const parsed = parsedBody.ok ? schema.safeParse(parsedBody.value) : null;
    if (parsed?.success === true) return parsed.data;
    if (isDevelopmentBuild() && parsed !== null && !parsed.success) {
      const fields = parsed.error.issues.map((issue) => issue.path.join('.')).join(', ');
      console.warn(EN['http.schemaMismatch'](fields));
    }
    throw new ApiError(raw.status, 'INTERNAL', null, raw.headers.get(HTTP_HEADERS.requestId));
  }

  async function request<Response>(call: ApiCall<Response>): Promise<Response> {
    const url = `${await deps.baseUrl()}${call.path}`;
    const init: RequestInit = {
      method: call.method,
      headers: await buildHeaders(call),
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

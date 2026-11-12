import type { ErrorCode } from '@kodakiko/contract';
import { ApiError, type ApiCall, type HttpClient } from '../../src/adapters/http/http';

export type RecordedRequest = {
  readonly method: string;
  readonly path: string;
  readonly body: unknown;
};

export type Handler = (request: RecordedRequest) => unknown;

export type FakeHttp = {
  readonly http: HttpClient;
  readonly requests: RecordedRequest[];
  readonly on: (method: string, path: string | RegExp, handler: Handler) => void;
  readonly count: (method: string, path: string | RegExp) => number;
};

export function apiFailure(
  status: number,
  code: ErrorCode,
  details: unknown = null,
  retryAfterSeconds: number | null = null,
): ApiError {
  return new ApiError(status, code, details, null, retryAfterSeconds);
}

function matches(pattern: string | RegExp, path: string): boolean {
  return typeof pattern === 'string' ? pattern === path : pattern.test(path);
}

export function createFakeHttp(): FakeHttp {
  const requests: RecordedRequest[] = [];
  const handlers: Array<{ method: string; path: string | RegExp; handler: Handler }> = [];
  const http: HttpClient = {
    request: async <Response>(call: ApiCall<Response>): Promise<Response> => {
      const request: RecordedRequest = { method: call.method, path: call.path, body: call.body };
      requests.push(request);
      const route = [...handlers]
        .reverse()
        .find((entry) => entry.method === call.method && matches(entry.path, call.path));
      if (route === undefined) throw apiFailure(404, 'NOT_FOUND');
      return call.responseSchema.parse(await route.handler(request));
    },
  };
  return {
    http,
    requests,
    on: (method, path, handler) => {
      handlers.push({ method, path, handler });
    },
    count: (method, path) =>
      requests.filter((request) => request.method === method && matches(path, request.path)).length,
  };
}

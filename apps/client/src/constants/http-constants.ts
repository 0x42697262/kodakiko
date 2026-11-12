export const HTTP_TIMEOUT_MS = 20_000;
export const HTTP_RETRY_ATTEMPTS = 3;
export const HTTP_RETRY_BASE_MS = 500;
export const HTTP_RETRY_CAP_MS = 8000;
export const HTTP_RETRY_AFTER_MAX_MS = 60_000;
export const HTTP_BACKOFF_FACTOR = 2;
export const MS_PER_SECOND = 1000;

export const HTTP_STATUS = Object.freeze({
  ok: 200,
  noContent: 204,
  multipleChoices: 300,
  unauthorized: 401,
  forbidden: 403,
  notFound: 404,
  conflict: 409,
  tooManyRequests: 429,
  serverErrorMin: 500,
});

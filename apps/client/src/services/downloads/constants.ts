export const DOWNLOAD_TIMEOUT_MS = 600_000;
export const DOWNLOAD_RETRY_DELAYS_MS: readonly number[] = Object.freeze([2_000, 8_000, 30_000]);
export const THUMBNAIL_FETCH_CONCURRENCY = 6;
export const ORIGINAL_FETCH_CONCURRENCY = 2;
export const HTTP_NOT_FOUND = 404;
export const HTTP_SERVER_ERROR_MIN = 500;
export const DOWNLOAD_PRIORITIES = Object.freeze(['opened', 'visible', 'background'] as const);

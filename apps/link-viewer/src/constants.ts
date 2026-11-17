export const HTTP_TIMEOUT_MS = 20_000;
export const HTTP_RETRY_ATTEMPTS = 3;
export const HTTP_RETRY_BASE_MS = 500;
export const HTTP_RETRY_CAP_MS = 8000;
export const HTTP_RETRY_AFTER_MAX_MS = 60_000;
export const HTTP_BACKOFF_FACTOR = 2;
export const MS_PER_SECOND = 1000;
export const MS_PER_MINUTE = 60_000;
export const MS_PER_DAY = 86_400_000;

export const HTTP_STATUS = Object.freeze({
  ok: 200,
  partialContent: 206,
  multipleChoices: 300,
  clientErrorMin: 400,
  forbidden: 403,
  gone: 410,
  rangeNotSatisfiable: 416,
  tooManyRequests: 429,
  serverErrorMin: 500,
  badGateway: 502,
});

export const FETCH_REDIRECT_POLICY = 'error' as const satisfies RequestRedirect;
export const OPAQUE_REDIRECT_RESPONSE = 'opaqueredirect' as const satisfies ResponseType;

export const BINARY_MIME_TYPE = 'application/octet-stream';
export const BLOB_MIME_TYPES: ReadonlySet<string> = new Set([
  'image/jpeg',
  'image/webp',
  'video/mp4',
  'video/quicktime',
  'video/webm',
]);

export const PAGE_HIDE_EVENT = 'pagehide';

export const KEY_BYTES = 32;
export const LINK_SECRET_BYTES = 32;
export const METADATA_VERSION = 1;
export const ONE_VIEW = 1;
export const PLAYBACK_ID_BYTES = 16;
export const HEX_RADIX = 16;
export const HEX_WIDTH = 2;

export const ISO_DATE_LENGTH = 10;
export const YEAR_LENGTH = 4;
export const DATE_PART_WIDTH = 2;
export const MONTH_OFFSET = 1;
export const DATE_LOCALE = 'en-US';
export const DATE_TIME_ZONE = 'UTC';

export const SWIPE_MIN_DISTANCE_PX = 60;
export const SWIPE_DIRECTION_RATIO = 1.5;
export const TAP_MAX_DISTANCE_PX = 10;

export const ZOOM_MIN = 1;
export const ZOOM_MAX = 5;
export const ZOOM_SNAP_BELOW = 1.05;
export const ZOOM_UNZOOMED = 1;
export const ZOOM_DOUBLE_TAP = 2.5;
export const DOUBLE_TAP_MS = 300;
export const WHEEL_ZOOM_SENSITIVITY = 0.002;
export const HALF = 2;
export const PINCH_POINTERS = 2;

export const DOWNLOAD_MAX_BYTES = 209_715_200;
export const MEMORY_PLAYBACK_MAX_BYTES = 209_715_200;
export const OBJECT_URL_CACHE_ITEMS = 1000;
export const ORIGINAL_CACHE_ITEMS = 3;
export const THUMBNAIL_CONCURRENCY = 4;

export const SW_MAX_RESPONSE_CHUNKS = 4;
export const SW_CHUNK_CACHE_SIZE = 4;
export const SW_KEY_REQUEST_TIMEOUT_MS = 5_000;
export const SW_CONTROL_TIMEOUT_MS = 5_000;
export const SW_PING_TIMEOUT_MS = 2_000;

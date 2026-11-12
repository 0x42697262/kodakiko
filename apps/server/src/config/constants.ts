const KIBIBYTE = 1024;
const MEBIBYTE = KIBIBYTE * KIBIBYTE;
const GIBIBYTE = MEBIBYTE * KIBIBYTE;
const SECOND_MS = 1000;
const MINUTE_MS = 60 * SECOND_MS;
const HOUR_MS = 60 * MINUTE_MS;
const MAX_UPLOAD_BYTES_CEILING = 3758096384;

export const API_MAX_BODY_BYTES = 2 * MEBIBYTE;
export const REQUEST_ID_BYTES = 16;
export const READY_CHECK_TIMEOUT_MS = 2000;
export const DATABASE_CHECK_NAME = 'database';

export const RATE_LIMITS = Object.freeze({
  auth: { max: 60, timeWindowMs: MINUTE_MS },
  authSignIn: { max: 10, timeWindowMs: 5 * MINUTE_MS },
  authSignUp: { max: 20, timeWindowMs: HOUR_MS },
  users: { max: 60, timeWindowMs: MINUTE_MS },
  collections: { max: 120, timeWindowMs: MINUTE_MS },
  entries: { max: 300, timeWindowMs: MINUTE_MS },
  media: { max: 300, timeWindowMs: MINUTE_MS },
  publicLinks: { max: 60, timeWindowMs: MINUTE_MS },
  publicLinkOpen: { max: 30, timeWindowMs: MINUTE_MS },
  publicLinkEntries: { max: 120, timeWindowMs: MINUTE_MS },
  publicLinkContent: { max: 1200, timeWindowMs: MINUTE_MS },
  admin: { max: 60, timeWindowMs: MINUTE_MS },
});

export type RateLimitGroup = keyof typeof RATE_LIMITS;

export const RATE_LIMIT_IP_KEYED_GROUPS: readonly RateLimitGroup[] = Object.freeze([
  'authSignIn',
  'authSignUp',
  'publicLinkOpen',
  'publicLinkEntries',
  'publicLinkContent',
]);

export const RATE_LIMIT_SESSION_KEY_PREFIX = 's:';
export const RATE_LIMIT_IP_KEY_PREFIX = 'ip:';
export const RATE_LIMIT_SESSION_HASH_HEX_CHARS = 16;
export const RATE_LIMIT_HEADERS_UNDER_LIMIT = Object.freeze({
  'x-ratelimit-limit': false,
  'x-ratelimit-remaining': true,
  'x-ratelimit-reset': false,
});
export const RATE_LIMIT_HEADERS_OVER_LIMIT = Object.freeze({
  ...RATE_LIMIT_HEADERS_UNDER_LIMIT,
  'retry-after': true,
});
export const HTTP_HEADERS = Object.freeze({
  requestId: 'x-request-id',
  cacheControl: 'cache-control',
  authorization: 'authorization',
});
export const CACHE_CONTROL_NO_STORE = 'no-store';
export const BEARER_PREFIX = 'Bearer ';

export const JOB_TICK_MS = 15 * SECOND_MS;
export const JOB_TIMEOUT_MS = 5 * MINUTE_MS;
export const JOB_BATCH_SIZE = 500;
export const SESSION_TOUCH_INTERVAL_MINUTES = 5;

export const HSTS_MAX_AGE_SECONDS = 63072000;
export const INT8_OID = 20;

export const TRASH_DAYS_DEFAULT = 30;
export const ACCOUNT_DELETION_GRACE_DAYS_DEFAULT = 7;
export const PUBLIC_CONFIG_API_VERSION = 1;

export const TRUSTED_PROXY_PRESETS = Object.freeze(['loopback', 'linklocal', 'uniquelocal']);
export const TRUSTED_PROXY_LIST_SEPARATOR = ',';
export const CIDR_SEPARATOR = '/';
export const CIDR_MAX_PREFIX: Readonly<Record<number, number>> = Object.freeze({ 4: 32, 6: 128 });
export const LOCAL_HOSTS = Object.freeze(['localhost', '127.0.0.1']);
export const INTEGER_PATTERN = /^\d+$/;
export const POSTGRES_PROTOCOLS = Object.freeze(['postgres:', 'postgresql:']);
export const HTTPS_SCHEME = 'https';
export const HTTPS_PROTOCOL = `${HTTPS_SCHEME}:`;
export const HTTP_PROTOCOL = 'http:';
export const LOG_LEVELS = Object.freeze([
  'fatal',
  'error',
  'warn',
  'info',
  'debug',
  'trace',
] as const);
export const BOOLEAN_VALUES = Object.freeze(['true', 'false'] as const);

export const SETTINGS_DEFAULTS = Object.freeze({
  port: 8080,
  host: '0.0.0.0',
  trustedProxies: 'loopback,uniquelocal',
  requireHttps: 'true',
  rateLimitsEnabled: 'true',
  serverVersion: 'dev',
  shutdownTimeoutMs: 15000,
  databasePoolMax: 10,
  databaseStatementTimeoutMs: 10000,
  databaseConnectTimeoutMs: 5000,
  flagSharing: 'false',
  flagPublicLinks: 'false',
  signupMode: 'invite',
  maxMediaPerEntry: 20,
  maxUploadBytes: MAX_UPLOAD_BYTES_CEILING,
  defaultQuotaBytes: 100 * GIBIBYTE,
  logLevel: 'info',
  jobsEnabled: 'true',
} as const);

export const SETTINGS_BOUNDS = Object.freeze({
  portMax: 65535,
  shutdownTimeoutMsMin: 1000,
  databasePoolMaxMax: 100,
  databaseTimeoutMsMin: 100,
  maxUploadBytesMin: MEBIBYTE,
  maxUploadBytesMax: MAX_UPLOAD_BYTES_CEILING,
});

export const API_CSP_DIRECTIVES = Object.freeze({
  defaultSrc: ["'none'"],
  frameAncestors: ["'none'"],
});
export const REFERRER_POLICY = 'no-referrer';
export const CORP_POLICY = 'same-origin';

export const HTTP_STATUS = Object.freeze({
  serviceUnavailable: 503,
});

export const EXIT_CODES = Object.freeze({
  success: 0,
  failure: 1,
  usage: 2,
});

export const API_ROOT_PATH = '/api';
export const API_PATH_PREFIX = `${API_ROOT_PATH}/`;
export const QUERY_SEPARATOR = '?';
export const ID_BYTES = 16;
export const FATAL_LOG_LEVEL = 'fatal';

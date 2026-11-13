const KIBIBYTE = 1024;
const MEBIBYTE = KIBIBYTE * KIBIBYTE;
const GIBIBYTE = MEBIBYTE * KIBIBYTE;
export const SECOND_MS = 1000;
export const MINUTE_MS = 60 * SECOND_MS;
export const HOUR_MS = 60 * MINUTE_MS;
export const DAY_MS = 24 * HOUR_MS;
const MAX_UPLOAD_BYTES_CEILING = 3758096384;

export const API_MAX_BODY_BYTES = 2 * MEBIBYTE;
export const REQUEST_ID_BYTES = 16;
export const READY_CHECK_TIMEOUT_MS = 2000;
export const DATABASE_CHECK_NAME = 'database';
export const BUCKET_CHECK_NAME = 'bucket';

export const RATE_LIMITS = Object.freeze({
  auth: { max: 60, timeWindowMs: MINUTE_MS },
  authPublic: { max: 60, timeWindowMs: MINUTE_MS },
  authSignIn: { max: 10, timeWindowMs: 5 * MINUTE_MS },
  authSignUp: { max: 20, timeWindowMs: HOUR_MS },
  authSignUpOpen: { max: 5, timeWindowMs: DAY_MS },
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
  'authPublic',
  'authSignIn',
  'authSignUp',
  'authSignUpOpen',
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
export const COLLECTIONS_PAGE_MAX = 200;
export const COLLECTIONS_PAGE_DEFAULT = 100;
export const DIFF_PAGE_DEFAULT = 500;
export const COLLATION_C = 'C';
export const COLLATE_KEYWORD = 'collate';
export const SESSION_TOUCH_INTERVAL_MINUTES = 5;

export const HSTS_MAX_AGE_SECONDS = 63072000;
export const INT8_OID = 20;

export const PUBLIC_CONFIG_API_VERSION = 1;

export const TRUSTED_PROXY_PRESETS = Object.freeze(['loopback', 'linklocal', 'uniquelocal']);
export const TRUSTED_PROXY_LIST_SEPARATOR = ',';
export const CIDR_SEPARATOR = '/';
export const CIDR_MAX_PREFIX: Readonly<Record<number, number>> = Object.freeze({ 4: 32, 6: 128 });
export const LOCAL_HOSTS = Object.freeze(['localhost', '127.0.0.1', '10.0.2.2']);
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
export const STORAGE_PROVIDERS = Object.freeze(['garage', 'b2', 'r2', 's3'] as const);

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
  sessionIdleDays: 30,
  maxFailedSignIns: 10,
  failedSignInWindowMinutes: 60,
  inviteDefaultExpiryDays: 7,
  accountDeletionGraceDays: 7,
  jobExpireSessionsIntervalMinutes: 1440,
  maxCollectionMetadataBytes: 4096,
  maxRecipientsPerCollection: 50,
  maxSharedCollectionsPerUser: 200,
  storageRegion: 'garage',
  storageForcePathStyle: 'true',
  storageRequestTimeoutMs: 15000,
  singlePartMaxBytes: 8 * MEBIBYTE,
  multipartPartBytes: 8 * MEBIBYTE,
  uploadUrlTtlSeconds: 86400,
  downloadUrlTtlSeconds: 900,
  pendingUploadHours: 48,
  objectGraceDays: 7,
  maxPresignsPerHour: 1000,
  jobCollectObjectsIntervalMinutes: 10,
  jobAbortStaleUploadsIntervalMinutes: 60,
  jobReconcileUsageIntervalMinutes: 1440,
  maxEntryMetadataBytes: 524288,
  maxDiffPageSize: 500,
  trashDays: 30,
  tombstoneRetentionDays: 90,
  jobPurgeTrashIntervalMinutes: 60,
  jobEraseAccountsIntervalMinutes: 60,
  jobPruneTombstonesIntervalMinutes: 1440,
} as const);

export const SETTINGS_BOUNDS = Object.freeze({
  portMax: 65535,
  shutdownTimeoutMsMin: 1000,
  databasePoolMaxMax: 100,
  databaseTimeoutMsMin: 100,
  maxUploadBytesMin: MEBIBYTE,
  maxUploadBytesMax: MAX_UPLOAD_BYTES_CEILING,
  sessionIdleDaysMax: 365,
  maxFailedSignInsMax: 100,
  failedSignInWindowMinutesMax: 1440,
  inviteDefaultExpiryDaysMax: 90,
  accountDeletionGraceDaysMax: 90,
  maxCollectionMetadataBytesMin: 256,
  maxRecipientsPerCollectionMax: 500,
  maxSharedCollectionsPerUserMax: 10_000,
  fakeParamsSecretMinBytes: 32,
  bucketNameMinChars: 3,
  bucketNameMaxChars: 63,
  storageRequestTimeoutMsMin: 1000,
  partBytesMin: 5 * MEBIBYTE,
  uploadUrlTtlSecondsMin: 60,
  uploadUrlTtlSecondsMax: 604800,
  downloadUrlTtlSecondsMin: 60,
  downloadUrlTtlSecondsMax: 86400,
  maxEntryMetadataBytesMin: 1024,
  maxDiffPageSizeMin: 10,
  maxDiffPageSizeMax: 2000,
  tombstoneRetentionDaysMin: 7,
});

export const SECONDS_PER_HOUR = 3600;
export const S3_MAX_PARTS = 10_000;
export const OBJECT_KEY_VERSION = 'v1';
export const OBJECT_KEY_SEPARATOR = '/';
export const OBJECT_CONTENT_TYPE = 'application/octet-stream';
export const PRESIGN_RATE_WINDOW_MS = HOUR_MS;
export const BACKOFF_GROWTH_FACTOR = 2;
export const STORAGE_RETRY_ATTEMPTS = 3;
export const STORAGE_RETRY_BASE_DELAY_MS = 200;
export const STORAGE_RETRY_CAP_DELAY_MS = 2000;
export const STORAGE_RETRY_STATUS_TOO_MANY_REQUESTS = 429;
export const S3_NOT_FOUND_ERROR_NAMES = Object.freeze(['NotFound', 'NoSuchKey']);
export const S3_NO_SUCH_UPLOAD_ERROR_NAME = 'NoSuchUpload';
export const S3_CHECKSUM_MODE = 'WHEN_REQUIRED';
export const S3_LIST_PARTS_PAGE_SIZE = 1000;
export const BUCKET_CORS_RULE = Object.freeze({
  methods: ['GET', 'PUT', 'HEAD'],
  headers: ['range', 'content-type'],
  exposeHeaders: ['Content-Range', 'Content-Length', 'Accept-Ranges'],
  maxAgeSeconds: 3600,
});
export const RANGE_HEADER_PREFIX = 'bytes=';
export const CONTENT_RANGE_TOTAL_SEPARATOR = '/';

export const API_CSP_DIRECTIVES = Object.freeze({
  defaultSrc: ["'none'"],
  frameAncestors: ["'none'"],
});
export const REFERRER_POLICY = 'no-referrer';
export const CORP_POLICY = 'same-origin';

export const HTTP_STATUS = Object.freeze({
  ok: 200,
  created: 201,
  noContent: 204,
  notFound: 404,
  tooManyRequests: 429,
  serverErrorMin: 500,
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

export const WRAPPED_KEY_BYTES = 73;
export const LOGIN_KEY_BYTES = 32;
export const PUBLIC_KEY_BYTES = 32;
export const FAILED_SIGN_IN_DELAY_CAP_MS = 30_000;
export const FAILED_SIGN_IN_BASE_DELAY_MS = 1000;
export const FAILED_SIGN_IN_TRACKER_MAX_ENTRIES = 10_000;
export const USERNAME_LOG_HASH_HEX_CHARS = 12;
export const DUMMY_LOGIN_HASH_SEED = 'kodakiko/dummy-login-hash';
export const FAKE_PARAMS_SALT_BYTES = 16;
export const FAKE_RECOVERY_WRAP_VERSION = 0x01;
export const FAKE_RECOVERY_WRAP_BYTES = 73;
export const FAKE_RECOVERY_ID_BYTES = 16;
export const INVITE_PATH = '/invite';
export const INVITE_FRAGMENT_PREFIX = '#';

export const HMAC_LABELS = Object.freeze({
  paramsSalt: 'params-salt-1',
  recoveryFake1: 'recovery-fake-1',
  recoveryFake2: 'recovery-fake-2',
  recoveryFake3: 'recovery-fake-3',
  recoveryFakeId: 'recovery-fake-id',
} as const);

export const PENDING_DELETION_ALLOWED_ROUTE_IDS: readonly string[] = Object.freeze([
  'authSession',
  'authSignOut',
  'usersMe',
  'usersMeDeleteCancel',
]);

export const PG_UNIQUE_VIOLATION = '23505';
export const USERS_USERNAME_CONSTRAINT = 'users_username_key';
export const USERS_ID_CONSTRAINT = 'users_pkey';
export const COLLECTIONS_ID_CONSTRAINT = 'collections_pkey';
export const OBJECTS_ID_CONSTRAINT = 'objects_pkey';
export const ENTRIES_ID_CONSTRAINT = 'entries_pkey';
export const MEDIA_ID_CONSTRAINT = 'media_pkey';
export const OBJECTS_PENDING_VARIANT_CONSTRAINT = 'objects_one_pending_per_variant';

export const CLI_FLAGS = Object.freeze({
  role: '--role',
  expiresDays: '--expires-days',
});
export const CLI_INVITE_EXPIRES_DAYS_MIN = 1;
export const CLI_INVITE_EXPIRES_DAYS_MAX = 90;

export const SEALED_SHARE_BYTES = 104;

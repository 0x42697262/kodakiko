import { isIP } from 'node:net';
import { z } from 'zod';
import { MEDIA_PER_ENTRY_HARD_MAX, SIGNUP_MODES, bytesSchema, decodeBytes, isDecodableBase64Url } from '@kodakiko/contract';
import { SettingsError } from '../domain/errors.ts';
import { SETTINGS_TEXT } from '../text/settings.ts';
import {
  BOOLEAN_VALUES,
  CIDR_MAX_PREFIX,
  CIDR_SEPARATOR,
  HTTPS_PROTOCOL,
  HTTP_PROTOCOL,
  INTEGER_PATTERN,
  LOCAL_HOSTS,
  MINUTE_MS,
  LOG_LEVELS,
  POSTGRES_PROTOCOLS,
  S3_MAX_PARTS,
  SECONDS_PER_HOUR,
  SETTINGS_BOUNDS,
  SETTINGS_DEFAULTS,
  STORAGE_PROVIDERS,
  TRUSTED_PROXY_LIST_SEPARATOR,
  TRUSTED_PROXY_PRESETS,
} from './constants.ts';

export type Settings = {
  readonly server: {
    readonly port: number;
    readonly host: string;
    readonly publicBaseUrl: string;
    readonly trustedProxies: readonly string[];
    readonly requireHttps: boolean;
    readonly rateLimitsEnabled: boolean;
    readonly version: string;
    readonly shutdownTimeoutMs: number;
  };
  readonly database: {
    readonly url: string;
    readonly poolMax: number;
    readonly statementTimeoutMs: number;
    readonly connectTimeoutMs: number;
  };
  readonly flags: {
    readonly sharing: boolean;
    readonly publicLinks: boolean;
    readonly signupMode: (typeof SIGNUP_MODES)[number];
  };
  readonly limits: {
    readonly maxMediaPerEntry: number;
    readonly maxUploadBytes: number;
    readonly defaultQuotaBytes: number;
    readonly maxCollectionMetadataBytes: number;
    readonly maxEntryMetadataBytes: number;
  };
  readonly sync: {
    readonly maxDiffPageSize: number;
    readonly tombstoneRetentionDays: number;
  };
  readonly entries: { readonly trashDays: number };
  readonly auth: {
    readonly fakeParamsSecret: Uint8Array;
    readonly sessionIdleDays: number;
    readonly maxFailedSignIns: number;
    readonly failedSignInWindowMinutes: number;
    readonly inviteDefaultExpiryDays: number;
  };
  readonly accounts: { readonly deletionGraceDays: number };
  readonly log: { readonly level: (typeof LOG_LEVELS)[number] };
  readonly storage: StorageSettings;
  readonly jobs: {
    readonly enabled: boolean;
    readonly expireSessionsIntervalMs: number;
    readonly collectObjectsIntervalMs: number;
    readonly abortStaleUploadsIntervalMs: number;
    readonly reconcileUsageIntervalMs: number;
    readonly purgeTrashIntervalMs: number;
    readonly eraseAccountsIntervalMs: number;
    readonly pruneTombstonesIntervalMs: number;
  };
};

export type StorageSettings = {
  readonly provider: (typeof STORAGE_PROVIDERS)[number];
  readonly endpoint: string;
  readonly publicEndpoint: string;
  readonly region: string;
  readonly bucket: string;
  readonly accessKeyId: string;
  readonly secretAccessKey: string;
  readonly forcePathStyle: boolean;
  readonly requestTimeoutMs: number;
  readonly singlePartMaxBytes: number;
  readonly multipartPartBytes: number;
  readonly uploadUrlTtlSeconds: number;
  readonly downloadUrlTtlSeconds: number;
  readonly pendingUploadHours: number;
  readonly objectGraceDays: number;
  readonly maxPresignsPerHour: number;
};

function integer(fallback: number | undefined, min: number, max: number) {
  const base = z
    .string()
    .regex(INTEGER_PATTERN, SETTINGS_TEXT.notInteger)
    .transform(Number)
    .pipe(z.number().int().min(min).max(max));
  return fallback === undefined ? base : base.default(fallback);
}

function flag(fallback: (typeof BOOLEAN_VALUES)[number]) {
  return z
    .enum(BOOLEAN_VALUES, { error: SETTINGS_TEXT.notBoolean })
    .default(fallback)
    .transform((value) => value === 'true');
}

function splitEntries(value: string): string[] {
  if (value.trim() === '') return [];
  return value.split(TRUSTED_PROXY_LIST_SEPARATOR).map((entry) => entry.trim());
}

function isTrustedProxyEntry(entry: string): boolean {
  if (TRUSTED_PROXY_PRESETS.includes(entry)) return true;
  const [address = '', prefix, ...rest] = entry.split(CIDR_SEPARATOR);
  const version = isIP(address);
  if (version === 0) return false;
  if (prefix === undefined) return true;
  if (rest.length > 0 || !INTEGER_PATTERN.test(prefix)) return false;
  const maxPrefix = CIDR_MAX_PREFIX[version];
  return maxPrefix !== undefined && Number(prefix) <= maxPrefix;
}

function parseUrl(value: string): URL | null {
  try {
    return new URL(value);
  } catch {
    return null;
  }
}

function isBaseUrl(value: string): boolean {
  const url = parseUrl(value);
  const web = url?.protocol === HTTPS_PROTOCOL || url?.protocol === HTTP_PROTOCOL;
  return web && url?.origin === value;
}

function isPostgresUrl(value: string): boolean {
  const url = parseUrl(value);
  return url !== null && POSTGRES_PROTOCOLS.includes(url.protocol);
}

const envSchema = z
  .object({
    PORT: integer(SETTINGS_DEFAULTS.port, 1, SETTINGS_BOUNDS.portMax),
    HOST: z.string().min(1).default(SETTINGS_DEFAULTS.host),
    PUBLIC_BASE_URL: z.string().refine(isBaseUrl, SETTINGS_TEXT.notHttpUrl),
    TRUSTED_PROXIES: z
      .string()
      .default(SETTINGS_DEFAULTS.trustedProxies)
      .transform(splitEntries)
      .superRefine((entries, context) => {
        for (const entry of entries.filter((item) => !isTrustedProxyEntry(item))) {
          context.addIssue({ code: 'custom', message: SETTINGS_TEXT.invalidProxyEntry(entry) });
        }
      }),
    REQUIRE_HTTPS: flag(SETTINGS_DEFAULTS.requireHttps),
    RATE_LIMITS_ENABLED: flag(SETTINGS_DEFAULTS.rateLimitsEnabled),
    SERVER_VERSION: z.string().min(1).default(SETTINGS_DEFAULTS.serverVersion),
    SHUTDOWN_TIMEOUT_MS: integer(
      SETTINGS_DEFAULTS.shutdownTimeoutMs,
      SETTINGS_BOUNDS.shutdownTimeoutMsMin,
      Number.MAX_SAFE_INTEGER,
    ),
    DATABASE_URL: z.string().refine(isPostgresUrl, SETTINGS_TEXT.notPostgresUrl),
    DATABASE_POOL_MAX: integer(
      SETTINGS_DEFAULTS.databasePoolMax,
      1,
      SETTINGS_BOUNDS.databasePoolMaxMax,
    ),
    DATABASE_STATEMENT_TIMEOUT_MS: integer(
      SETTINGS_DEFAULTS.databaseStatementTimeoutMs,
      SETTINGS_BOUNDS.databaseTimeoutMsMin,
      Number.MAX_SAFE_INTEGER,
    ),
    DATABASE_CONNECT_TIMEOUT_MS: integer(
      SETTINGS_DEFAULTS.databaseConnectTimeoutMs,
      SETTINGS_BOUNDS.databaseTimeoutMsMin,
      Number.MAX_SAFE_INTEGER,
    ),
    FLAG_SHARING: flag(SETTINGS_DEFAULTS.flagSharing),
    FLAG_PUBLIC_LINKS: flag(SETTINGS_DEFAULTS.flagPublicLinks),
    SIGNUP_MODE: z.enum(SIGNUP_MODES).default(SETTINGS_DEFAULTS.signupMode),
    MAX_MEDIA_PER_ENTRY: integer(SETTINGS_DEFAULTS.maxMediaPerEntry, 1, MEDIA_PER_ENTRY_HARD_MAX),
    MAX_UPLOAD_BYTES: integer(
      SETTINGS_DEFAULTS.maxUploadBytes,
      SETTINGS_BOUNDS.maxUploadBytesMin,
      SETTINGS_BOUNDS.maxUploadBytesMax,
    ),
    DEFAULT_QUOTA_BYTES: integer(SETTINGS_DEFAULTS.defaultQuotaBytes, 0, Number.MAX_SAFE_INTEGER),
    LOG_LEVEL: z.enum(LOG_LEVELS).default(SETTINGS_DEFAULTS.logLevel),
    JOBS_ENABLED: flag(SETTINGS_DEFAULTS.jobsEnabled),
    FAKE_PARAMS_SECRET: bytesSchema
      .refine(
        (value) =>
          isDecodableBase64Url(value) &&
          decodeBytes(value).length >= SETTINGS_BOUNDS.fakeParamsSecretMinBytes,
        SETTINGS_TEXT.fakeParamsSecretTooShort,
      )
      .transform((value) => decodeBytes(value)),
    SESSION_IDLE_DAYS: integer(
      SETTINGS_DEFAULTS.sessionIdleDays,
      1,
      SETTINGS_BOUNDS.sessionIdleDaysMax,
    ),
    MAX_FAILED_SIGN_INS: integer(
      SETTINGS_DEFAULTS.maxFailedSignIns,
      1,
      SETTINGS_BOUNDS.maxFailedSignInsMax,
    ),
    FAILED_SIGN_IN_WINDOW_MINUTES: integer(
      SETTINGS_DEFAULTS.failedSignInWindowMinutes,
      1,
      SETTINGS_BOUNDS.failedSignInWindowMinutesMax,
    ),
    INVITE_DEFAULT_EXPIRY_DAYS: integer(
      SETTINGS_DEFAULTS.inviteDefaultExpiryDays,
      1,
      SETTINGS_BOUNDS.inviteDefaultExpiryDaysMax,
    ),
    ACCOUNT_DELETION_GRACE_DAYS: integer(
      SETTINGS_DEFAULTS.accountDeletionGraceDays,
      1,
      SETTINGS_BOUNDS.accountDeletionGraceDaysMax,
    ),
    JOB_EXPIRE_SESSIONS_INTERVAL_MINUTES: integer(
      SETTINGS_DEFAULTS.jobExpireSessionsIntervalMinutes,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    MAX_COLLECTION_METADATA_BYTES: integer(
      SETTINGS_DEFAULTS.maxCollectionMetadataBytes,
      SETTINGS_BOUNDS.maxCollectionMetadataBytesMin,
      Number.MAX_SAFE_INTEGER,
    ),
    STORAGE_PROVIDER: z.enum(STORAGE_PROVIDERS),
    S3_ENDPOINT: z.string().refine(isBaseUrl, SETTINGS_TEXT.notHttpUrl),
    S3_PUBLIC_ENDPOINT: z.string().refine(isBaseUrl, SETTINGS_TEXT.notHttpUrl),
    S3_REGION: z.string().min(1).default(SETTINGS_DEFAULTS.storageRegion),
    S3_BUCKET: z
      .string()
      .min(SETTINGS_BOUNDS.bucketNameMinChars)
      .max(SETTINGS_BOUNDS.bucketNameMaxChars),
    S3_ACCESS_KEY_ID: z.string().min(1),
    S3_SECRET_ACCESS_KEY: z.string().min(1),
    S3_FORCE_PATH_STYLE: flag(SETTINGS_DEFAULTS.storageForcePathStyle),
    STORAGE_REQUEST_TIMEOUT_MS: integer(
      SETTINGS_DEFAULTS.storageRequestTimeoutMs,
      SETTINGS_BOUNDS.storageRequestTimeoutMsMin,
      Number.MAX_SAFE_INTEGER,
    ),
    SINGLE_PART_MAX_BYTES: integer(
      SETTINGS_DEFAULTS.singlePartMaxBytes,
      SETTINGS_BOUNDS.partBytesMin,
      Number.MAX_SAFE_INTEGER,
    ),
    MULTIPART_PART_BYTES: integer(
      SETTINGS_DEFAULTS.multipartPartBytes,
      SETTINGS_BOUNDS.partBytesMin,
      Number.MAX_SAFE_INTEGER,
    ),
    UPLOAD_URL_TTL_SECONDS: integer(
      SETTINGS_DEFAULTS.uploadUrlTtlSeconds,
      SETTINGS_BOUNDS.uploadUrlTtlSecondsMin,
      SETTINGS_BOUNDS.uploadUrlTtlSecondsMax,
    ),
    DOWNLOAD_URL_TTL_SECONDS: integer(
      SETTINGS_DEFAULTS.downloadUrlTtlSeconds,
      SETTINGS_BOUNDS.downloadUrlTtlSecondsMin,
      SETTINGS_BOUNDS.downloadUrlTtlSecondsMax,
    ),
    PENDING_UPLOAD_HOURS: integer(
      SETTINGS_DEFAULTS.pendingUploadHours,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    OBJECT_GRACE_DAYS: integer(SETTINGS_DEFAULTS.objectGraceDays, 0, Number.MAX_SAFE_INTEGER),
    MAX_PRESIGNS_PER_HOUR: integer(
      SETTINGS_DEFAULTS.maxPresignsPerHour,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    JOB_COLLECT_OBJECTS_INTERVAL_MINUTES: integer(
      SETTINGS_DEFAULTS.jobCollectObjectsIntervalMinutes,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    JOB_ABORT_STALE_UPLOADS_INTERVAL_MINUTES: integer(
      SETTINGS_DEFAULTS.jobAbortStaleUploadsIntervalMinutes,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    JOB_RECONCILE_USAGE_INTERVAL_MINUTES: integer(
      SETTINGS_DEFAULTS.jobReconcileUsageIntervalMinutes,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    MAX_ENTRY_METADATA_BYTES: integer(
      SETTINGS_DEFAULTS.maxEntryMetadataBytes,
      SETTINGS_BOUNDS.maxEntryMetadataBytesMin,
      Number.MAX_SAFE_INTEGER,
    ),
    MAX_DIFF_PAGE_SIZE: integer(
      SETTINGS_DEFAULTS.maxDiffPageSize,
      SETTINGS_BOUNDS.maxDiffPageSizeMin,
      SETTINGS_BOUNDS.maxDiffPageSizeMax,
    ),
    TRASH_DAYS: integer(SETTINGS_DEFAULTS.trashDays, 1, Number.MAX_SAFE_INTEGER),
    TOMBSTONE_RETENTION_DAYS: integer(
      SETTINGS_DEFAULTS.tombstoneRetentionDays,
      SETTINGS_BOUNDS.tombstoneRetentionDaysMin,
      Number.MAX_SAFE_INTEGER,
    ),
    JOB_PURGE_TRASH_INTERVAL_MINUTES: integer(
      SETTINGS_DEFAULTS.jobPurgeTrashIntervalMinutes,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    JOB_ERASE_ACCOUNTS_INTERVAL_MINUTES: integer(
      SETTINGS_DEFAULTS.jobEraseAccountsIntervalMinutes,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    JOB_PRUNE_TOMBSTONES_INTERVAL_MINUTES: integer(
      SETTINGS_DEFAULTS.jobPruneTombstonesIntervalMinutes,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
  })
  .superRefine((env, context) => {
    const baseUrl = parseUrl(env.PUBLIC_BASE_URL);
    if (baseUrl?.protocol === HTTP_PROTOCOL && env.REQUIRE_HTTPS) {
      context.addIssue({
        code: 'custom',
        path: ['PUBLIC_BASE_URL'],
        message: SETTINGS_TEXT.httpNeedsHttpsOff,
      });
    }
    if (parseUrl(env.S3_PUBLIC_ENDPOINT)?.protocol === HTTP_PROTOCOL && env.REQUIRE_HTTPS) {
      context.addIssue({
        code: 'custom',
        path: ['S3_PUBLIC_ENDPOINT'],
        message: SETTINGS_TEXT.publicEndpointNeedsHttpsOff,
      });
    }
    if (env.MAX_UPLOAD_BYTES > env.MULTIPART_PART_BYTES * S3_MAX_PARTS) {
      context.addIssue({
        code: 'custom',
        path: ['MAX_UPLOAD_BYTES'],
        message: SETTINGS_TEXT.uploadExceedsPartCapacity(S3_MAX_PARTS),
      });
    }
    if (env.PENDING_UPLOAD_HOURS * SECONDS_PER_HOUR <= env.UPLOAD_URL_TTL_SECONDS) {
      context.addIssue({
        code: 'custom',
        path: ['PENDING_UPLOAD_HOURS'],
        message: SETTINGS_TEXT.pendingWindowTooShort,
      });
    }
    if (env.SINGLE_PART_MAX_BYTES > env.MAX_UPLOAD_BYTES) {
      context.addIssue({
        code: 'custom',
        path: ['SINGLE_PART_MAX_BYTES'],
        message: SETTINGS_TEXT.singlePartAboveMaxUpload,
      });
    }
    if (env.SINGLE_PART_MAX_BYTES > env.MULTIPART_PART_BYTES) {
      context.addIssue({
        code: 'custom',
        path: ['SINGLE_PART_MAX_BYTES'],
        message: SETTINGS_TEXT.singlePartAbovePartSize,
      });
    }
    const localOnly = !env.REQUIRE_HTTPS && LOCAL_HOSTS.includes(baseUrl?.hostname ?? '');
    if (!env.RATE_LIMITS_ENABLED && !localOnly) {
      context.addIssue({
        code: 'custom',
        path: ['RATE_LIMITS_ENABLED'],
        message: SETTINGS_TEXT.rateLimitsOffLocalOnly,
      });
    }
  });

type Env = z.output<typeof envSchema>;

function toSettings(env: Env): Settings {
  return {
    server: {
      port: env.PORT,
      host: env.HOST,
      publicBaseUrl: env.PUBLIC_BASE_URL,
      trustedProxies: env.TRUSTED_PROXIES,
      requireHttps: env.REQUIRE_HTTPS,
      rateLimitsEnabled: env.RATE_LIMITS_ENABLED,
      version: env.SERVER_VERSION,
      shutdownTimeoutMs: env.SHUTDOWN_TIMEOUT_MS,
    },
    database: {
      url: env.DATABASE_URL,
      poolMax: env.DATABASE_POOL_MAX,
      statementTimeoutMs: env.DATABASE_STATEMENT_TIMEOUT_MS,
      connectTimeoutMs: env.DATABASE_CONNECT_TIMEOUT_MS,
    },
    flags: {
      sharing: env.FLAG_SHARING,
      publicLinks: env.FLAG_PUBLIC_LINKS,
      signupMode: env.SIGNUP_MODE,
    },
    limits: {
      maxMediaPerEntry: env.MAX_MEDIA_PER_ENTRY,
      maxUploadBytes: env.MAX_UPLOAD_BYTES,
      defaultQuotaBytes: env.DEFAULT_QUOTA_BYTES,
      maxCollectionMetadataBytes: env.MAX_COLLECTION_METADATA_BYTES,
      maxEntryMetadataBytes: env.MAX_ENTRY_METADATA_BYTES,
    },
    sync: {
      maxDiffPageSize: env.MAX_DIFF_PAGE_SIZE,
      tombstoneRetentionDays: env.TOMBSTONE_RETENTION_DAYS,
    },
    entries: { trashDays: env.TRASH_DAYS },
    auth: {
      fakeParamsSecret: env.FAKE_PARAMS_SECRET,
      sessionIdleDays: env.SESSION_IDLE_DAYS,
      maxFailedSignIns: env.MAX_FAILED_SIGN_INS,
      failedSignInWindowMinutes: env.FAILED_SIGN_IN_WINDOW_MINUTES,
      inviteDefaultExpiryDays: env.INVITE_DEFAULT_EXPIRY_DAYS,
    },
    accounts: { deletionGraceDays: env.ACCOUNT_DELETION_GRACE_DAYS },
    log: { level: env.LOG_LEVEL },
    storage: {
      provider: env.STORAGE_PROVIDER,
      endpoint: env.S3_ENDPOINT,
      publicEndpoint: env.S3_PUBLIC_ENDPOINT,
      region: env.S3_REGION,
      bucket: env.S3_BUCKET,
      accessKeyId: env.S3_ACCESS_KEY_ID,
      secretAccessKey: env.S3_SECRET_ACCESS_KEY,
      forcePathStyle: env.S3_FORCE_PATH_STYLE,
      requestTimeoutMs: env.STORAGE_REQUEST_TIMEOUT_MS,
      singlePartMaxBytes: env.SINGLE_PART_MAX_BYTES,
      multipartPartBytes: env.MULTIPART_PART_BYTES,
      uploadUrlTtlSeconds: env.UPLOAD_URL_TTL_SECONDS,
      downloadUrlTtlSeconds: env.DOWNLOAD_URL_TTL_SECONDS,
      pendingUploadHours: env.PENDING_UPLOAD_HOURS,
      objectGraceDays: env.OBJECT_GRACE_DAYS,
      maxPresignsPerHour: env.MAX_PRESIGNS_PER_HOUR,
    },
    jobs: {
      enabled: env.JOBS_ENABLED,
      expireSessionsIntervalMs: env.JOB_EXPIRE_SESSIONS_INTERVAL_MINUTES * MINUTE_MS,
      collectObjectsIntervalMs: env.JOB_COLLECT_OBJECTS_INTERVAL_MINUTES * MINUTE_MS,
      abortStaleUploadsIntervalMs: env.JOB_ABORT_STALE_UPLOADS_INTERVAL_MINUTES * MINUTE_MS,
      reconcileUsageIntervalMs: env.JOB_RECONCILE_USAGE_INTERVAL_MINUTES * MINUTE_MS,
      purgeTrashIntervalMs: env.JOB_PURGE_TRASH_INTERVAL_MINUTES * MINUTE_MS,
      eraseAccountsIntervalMs: env.JOB_ERASE_ACCOUNTS_INTERVAL_MINUTES * MINUTE_MS,
      pruneTombstonesIntervalMs: env.JOB_PRUNE_TOMBSTONES_INTERVAL_MINUTES * MINUTE_MS,
    },
  };
}

function deepFreeze<Value extends object>(value: Value): Value {
  for (const child of Object.values(value)) {
    if (typeof child === 'object' && child !== null && !ArrayBuffer.isView(child)) {
      deepFreeze(child);
    }
  }
  return Object.freeze(value);
}

export function loadSettings(env: Readonly<Record<string, string | undefined>>): Settings {
  const result = envSchema.safeParse(env);
  if (!result.success) {
    const reasons = result.error.issues.map(
      (issue) => `${issue.path.join('.')}${SETTINGS_TEXT.reasonSeparator}${issue.message}`,
    );
    throw new SettingsError(
      `${SETTINGS_TEXT.invalidPrefix} ${reasons.join(SETTINGS_TEXT.separator)}`,
    );
  }
  return deepFreeze(toSettings(result.data));
}

import { isIP } from 'node:net';
import { z } from 'zod';
import { MEDIA_PER_ENTRY_HARD_MAX, SIGNUP_MODES } from '@kodakiko/contract';
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
  LOG_LEVELS,
  POSTGRES_PROTOCOLS,
  SETTINGS_BOUNDS,
  SETTINGS_DEFAULTS,
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
  };
  readonly log: { readonly level: (typeof LOG_LEVELS)[number] };
  readonly jobs: { readonly enabled: boolean };
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
    },
    log: { level: env.LOG_LEVEL },
    jobs: { enabled: env.JOBS_ENABLED },
  };
}

function deepFreeze<Value extends object>(value: Value): Value {
  for (const child of Object.values(value)) {
    if (typeof child === 'object' && child !== null) deepFreeze(child);
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

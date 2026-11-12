# 03 — Contract foundations + server skeleton

Status: draft 1 (2026-11-11).

Goal: a running Fastify server with validated config, the full Postgres schema migrated at boot,
the shared error model, request ids, redacted JSON logs, rate-limit plumbing, health and config
endpoints, a job scheduler, graceful shutdown, and an integration-test harness against a real
Postgres. No auth, storage or feature routes yet (specs 04-05 add them on top of this).

Read first: `docs/architecture.md` §4.1; `docs/design/api.md` §§1, 2, 11, 12;
`docs/design/data-model.md` §§1-3, 5; `docs/design/security.md` §§3-5; `docs/design/deployment.md`
§3; guidelines §§3, 4, 6, 7. Depends on spec 01.

Conventions (also for every later server spec):

- Layers: `http/routes` (definitions) → `http/controllers` (5-15 lines) → `services` → `domain`
  (pure) → `adapters`. Controllers never touch Kysely; services never touch Fastify types.
- Relative imports end in `.ts`.
- Every user-facing or log string comes from `src/text/`; every number from `config/constants.ts`
  or validated settings.

## 1. Contract additions (`packages/contract/src/`)

| File | Content |
|---|---|
| `errors.ts` | `ERROR_CODES` = frozen array of every code in `api.md` §2 (including `HTTPS_REQUIRED`); `ErrorCode` = its union type; `errorResponseSchema` = `z.object({ error: z.object({ code: z.enum(ERROR_CODES), message: z.string(), requestId: z.string(), details: z.unknown().nullable() }) })` |
| `config.ts` | `SIGNUP_MODES = ['invite', 'open'] as const`; `publicConfigSchema` = `z.object({ apiVersion: z.literal(1), signupMode: z.enum(SIGNUP_MODES), sharingEnabled: z.boolean(), publicLinksEnabled: z.boolean(), maxMediaPerEntry: z.number().int().positive(), maxUploadBytes: z.number().int().positive(), defaultQuotaBytes: z.number().int().nonnegative(), trashDays: z.number().int().positive(), accountDeletionGraceDays: z.number().int().positive(), serverVersion: z.string() })` and its type `PublicConfigDto` |
| `payloads.ts` | `TagName`, `MediaDescriptorV1`, `EntryMetadataV1`, `CollectionMetadataV1` exactly as `data-model.md` §6.1-6.3, with constants from a new `packages/contract/src/limits.ts`: `NOTE_MAX_CHARS = 100_000`, `TAGS_PER_ENTRY_MAX = 50`, `TAG_MAX_CHARS = 64`, `MEDIA_PER_ENTRY_HARD_MAX = 100`, `UTC_OFFSET_MINUTES_MIN = -840`, `UTC_OFFSET_MINUTES_MAX = 840`, `TIME_ZONE_MAX_CHARS = 64`, `MIME_TYPE_MAX_CHARS = 100`, `FILE_NAME_MAX_CHARS = 255`, `COLLECTION_NAME_MAX_CHARS = 100`, `LATITUDE_MIN = -90`, `LATITUDE_MAX = 90`, `LONGITUDE_MIN = -180`, `LONGITUDE_MAX = 180`. `TagName` here is only the shape check (`z.string().min(1).max(TAG_MAX_CHARS)`); normalization lives in core (`normalizeTag`, spec 08) |
| `routes.ts` | `API_PREFIX = '/api/v1'`; two frozen objects with the same keys: `ROUTE_PATTERNS` (full Fastify patterns, used by the server: `config: '/api/v1/config'`, `healthLive: '/health/live'`, `healthReady: '/health/ready'`, later e.g. `entry: '/api/v1/entries/:id'`) and `ROUTES` (path builders with the same full paths, used by clients: `config: () => '/api/v1/config'`, later `entry: (id) => \`/api/v1/entries/${id}\``). A test asserts that every key exists in both and that each builder, called with `':id'`-style placeholders, returns its pattern. Later specs add entries to both. No route string literal anywhere else |
| `index.ts` | re-exports all files |

Tests (`packages/contract/test/payloads.test.ts`): a valid `EntryMetadataV1` parses; each limit
+1 fails (note length, tags count, media count, offset range); `media: []` fails.

## 2. Server files (`apps/server/`)

### 2.1 Package

`package.json` dependencies (all `catalog:`): `fastify`, `fastify-type-provider-zod`,
`@fastify/rate-limit`, `@fastify/helmet`, `kysely`, `pg`, `zod`, `@kodakiko/contract`
(`workspace:*`), `@kodakiko/core` (`workspace:*`); devDependencies `@types/pg`, `@types/node`.
Scripts: `typecheck`, `test` = `vitest run --project unit`, `test:integration` =
`vitest run --project integration`, `build` = `node scripts/build.ts`, `start` =
`node dist/main.js`, `migrate:status` = `node dist/cli.js migrate-status`.

`vitest.config.ts`: two projects. `unit`: `test/unit/**/*.test.ts`. `integration`:
`test/integration/**/*.test.ts`, `globalSetup: test/integration/global-setup.ts`,
`fileParallelism: false`.

`scripts/build.ts`: esbuild `build()` for `src/main.ts` and `src/cli.ts` → `dist/`, `bundle: true`,
`platform: 'node'`, `format: 'esm'`, `target: 'node22'`, `external: ['pg-native']`, `sourcemap:
true`, `banner: { js: "import { createRequire } from 'node:module'; const require = createRequire(import.meta.url);" }`
(lets bundled CommonJS dependencies call `require`). The banner string is configuration, not a
comment.

`.env.example`: every variable of §2.3 with its default or an empty value for required ones.

### 2.2 Directory layout

```
apps/server/src/
  main.ts                  boot (§2.6)
  cli.ts                   operational commands (§2.11)
  app.ts                   buildApp(deps) composition (§2.6)
  config/settings.ts       env schema → Settings (§2.3)
  config/constants.ts      internal tuning (§2.4)
  text/errors.ts           message per ErrorCode
  text/events.ts           log event names
  domain/errors.ts         AppError + subclasses (§2.5)
  http/error-handler.ts    setErrorHandler + setNotFoundHandler (§2.5)
  http/log-redaction.ts    loggerOptions, REDACT_PATHS (§2.6)
  testing.ts               re-exports for the e2e package (spec 01 §1.3)
  http/request-context.ts  request id, HTTPS check (§2.7)
  http/rate-limits.ts      route-group limits (§2.8)
  http/routes/health.ts
  http/routes/config.ts
  http/controllers/config-controller.ts
  services/readiness.ts    ReadinessCheck = { name: string; check(): Promise<void> }
  adapters/db/database.ts  Kysely + pg pool (§2.9)
  adapters/db/schema.ts    Database interface (§2.9)
  adapters/db/migrations/index.ts
  adapters/db/migrations/0001_initial.ts
  adapters/db/migrator.ts
  jobs/scheduler.ts        (§2.10)
  jobs/keys.ts             advisory lock ids
  platform/clock.ts        Clock
  platform/ids.ts          randomId, randomToken
```

### 2.3 Settings (`config/settings.ts`)

`loadSettings(env: Readonly<Record<string, string | undefined>>): Settings` — the only reader of
environment variables in the server (guidelines 4.3). Zod schema; on failure throw
`SettingsError` listing every invalid variable name and reason (never the value). The result is
deep-frozen.

| Variable | Type | Default | Settings path |
|---|---|---|---|
| `PORT` | int 1-65535 | 8080 | `server.port` |
| `HOST` | string | `0.0.0.0` | `server.host` |
| `PUBLIC_BASE_URL` | https URL, no trailing slash (http allowed only when `REQUIRE_HTTPS=false`) | required | `server.publicBaseUrl` |
| `TRUSTED_PROXIES` | comma-separated list; each entry is an IP address, a CIDR (`10.0.0.0/8`, `fd00::/8`) or one of the presets `loopback`, `linklocal`, `uniquelocal`; empty = trust no proxy | `loopback,uniquelocal` | `server.trustedProxies` (readonly string array, entries trimmed; an invalid entry → `SettingsError` naming it). Passed to Fastify's `trustProxy` as the array: a request's `X-Forwarded-*` headers are honored only when the connecting peer matches (D-77). Fastify 5.12 treats a **number** as "trust nothing" (fail-closed), so hop counts are not used. The API port must never be published to clients: only Caddy (on the private Docker network) may reach it |
| `REQUIRE_HTTPS` | boolean | `true` | `server.requireHttps` |
| `RATE_LIMITS_ENABLED` | boolean | `true` | `server.rateLimitsEnabled`; `false` is accepted only when `REQUIRE_HTTPS=false` **and** the `PUBLIC_BASE_URL` host is `localhost`, `127.0.0.1` or `10.0.2.2` (the Android emulator's alias for the host loopback, D-81), else `SettingsError` (D-75: local UI tests only); when `false` the boot logs `security.rate_limits_disabled` at `warn` |
| `SERVER_VERSION` | string | `dev` | `server.version` |
| `SHUTDOWN_TIMEOUT_MS` | int ≥ 1000 | 15000 | `server.shutdownTimeoutMs` |
| `DATABASE_URL` | postgres URL | required | `database.url` |
| `DATABASE_POOL_MAX` | int 1-100 | 10 | `database.poolMax` |
| `DATABASE_STATEMENT_TIMEOUT_MS` | int ≥ 100 | 10000 | `database.statementTimeoutMs` |
| `DATABASE_CONNECT_TIMEOUT_MS` | int ≥ 100 | 5000 | `database.connectTimeoutMs` |
| `FLAG_SHARING` | boolean | `false` | `flags.sharing` |
| `FLAG_PUBLIC_LINKS` | boolean | `false` | `flags.publicLinks` |
| `SIGNUP_MODE` | `invite`/`open` | `invite` | `flags.signupMode` |
| `MAX_MEDIA_PER_ENTRY` | int 1-`MEDIA_PER_ENTRY_HARD_MAX` | 20 | `limits.maxMediaPerEntry` |
| `MAX_UPLOAD_BYTES` | int 1 MiB - 3,758,096,384 | 3758096384 (3.5 GiB) | `limits.maxUploadBytes` (upper bound: every file must fit a zip part of the journal export, D-66; fflate writes no Zip64) |
| `DEFAULT_QUOTA_BYTES` | int ≥ 0 | 107374182400 (100 GiB) | `limits.defaultQuotaBytes` |
| `LOG_LEVEL` | `fatal`…`trace` | `info` | `log.level` |
| `JOBS_ENABLED` | boolean | `true` | `jobs.enabled` |

Booleans accept exactly `true` or `false` (anything else is invalid). Specs 04-05 add their
groups (`auth`, `storage`, job intervals) to this same schema.

### 2.4 Constants (`config/constants.ts`)

`API_MAX_BODY_BYTES = 2 * 1024 * 1024`; `REQUEST_ID_BYTES = 16`; `READY_CHECK_TIMEOUT_MS = 2000`;
`RATE_LIMITS` (the table of `api.md` §1.1 as data: `{ auth: { max: 60, timeWindowMs: 60_000 },
authSignIn: { max: 10, timeWindowMs: 300_000 }, authSignUp: { max: 20, timeWindowMs: 3_600_000 },
users: …, collections: …, entries: …, media: …, publicLinks: …, publicLinkOpen: { max: 30, timeWindowMs: 60_000 }, publicLinkEntries: { max: 120,
timeWindowMs: 60_000 }, publicLinkContent: { max: 1200, timeWindowMs: 60_000 }, admin: … }`); `JOB_TICK_MS = 15_000`; `JOB_TIMEOUT_MS = 300_000` (the `timeoutMs` of every job in specs 04-06
unless a spec says otherwise); `JOB_BATCH_SIZE = 500`; `SESSION_TOUCH_INTERVAL_MINUTES = 5`
(used by spec 04).

### 2.5 Errors

`domain/errors.ts`:

```ts
export class AppError extends Error {
  readonly code: ErrorCode;
  readonly details: unknown;
  constructor(code: ErrorCode, details: unknown = null) { super(code); this.code = code; this.details = details; }
}
export class SettingsError extends Error {}
```

`http/error-handler.ts`:

- `HTTP_STATUS_BY_CODE`: frozen map `ErrorCode → status` exactly as `api.md` §2.
- `registerErrorHandling(app)` (reads `ERROR_TEXT` directly):
  - `AppError` → status from the map, body `{ error: { code, message: ERROR_TEXT[code], requestId, details } }`;
  - Fastify validation errors (from the Zod type provider: `error.validation` present or
    `hasZodFastifySchemaValidationErrors(error)`) → `422 VALIDATION_FAILED`, `details: { issues }`
    with each issue's `path` and `message` only;
  - body too large (`FST_ERR_CTP_BODY_TOO_LARGE`) → `413 PAYLOAD_TOO_LARGE`;
  - invalid JSON (`FST_ERR_CTP_INVALID_JSON_BODY` / content-type errors) → `400 MALFORMED_REQUEST`;
  - rate-limit errors → `429 RATE_LIMITED` (the plugin's `errorResponseBuilder` throws an
    `AppError('RATE_LIMITED')`);
  - anything else → `500 INTERNAL`, logged with event `http.unhandled_error` and the stack;
    message is the generic catalog text.
  - `setNotFoundHandler` → `404 NOT_FOUND`.
- Response serialization of errors always uses `errorResponseSchema`.

`text/errors.ts`: `ERROR_TEXT: Readonly<Record<ErrorCode, string>>`, one plain sentence each
(e.g. `NOT_FOUND`: "This item isn't yours and isn't shared with you, or it doesn't exist.").
TypeScript's `Record<ErrorCode, …>` makes a missing code a type error.

### 2.6 Composition and boot

`app.ts`:

```ts
export type AppDeps = {
  readonly settings: Settings;
  readonly db: Kysely<Database>;
  readonly clock: Clock;
  readonly readinessChecks: readonly ReadinessCheck[];
};
export async function buildApp(deps: AppDeps): Promise<FastifyInstance>;
```

`buildApp`:
1. `Fastify({ logger: loggerOptions(settings), trustProxy: [...settings.server.trustedProxies],
   bodyLimit: API_MAX_BODY_BYTES, genReqId: () => randomToken(REQUEST_ID_BYTES),
   requestIdHeader: false })` (request logging stays on by default; passing `disableRequestLogging`
   prints Fastify deprecation `FSTDEP023`, so the option is omitted).
2. `setValidatorCompiler(validatorCompiler)`, `setSerializerCompiler(serializerCompiler)` from
   `fastify-type-provider-zod`; `withTypeProvider<ZodTypeProvider>()`.
3. `registerRequestContext` (§2.7), `@fastify/helmet` (§2.7), `@fastify/rate-limit` with
   `global: false` (§2.8), `registerErrorHandling`.
4. Routes: health (no rate limit) and config, each registered with its full `ROUTE_PATTERNS`
   entry; no Fastify `prefix` option is used anywhere (patterns already contain `/api/v1`).
5. Returns the instance (not listening).

`http/log-redaction.ts` exports `loggerOptions(settings)` = `{ level, redact: { paths: REDACT_PATHS,
censor: '[redacted]' } }` and `REDACT_PATHS`:
`req.headers.authorization`, `req.headers["x-kodakiko-link-token"]`,
`req.headers["x-kodakiko-view-token"]`, `req.headers.cookie`, `*.entryKeyByCollection`,
`*.mediaKeyByEntry`, `*.keyMaterial`,
`*.password`, `*.loginKey`, `*.currentLoginKey`, `*.recoveryLoginKey`, `*.token`,
`*.inviteToken`, `*.accessToken`, `*.masterKeyByKek`, `*.masterKeyByRecovery`,
`*.recoveryKeyByMaster`, `*.privateKeyByMaster`, `*.keyByOwner`, `*.sealedCollectionKey`, and the
**bare top-level form** of every `*.x` path above (`password`, `token`, `loginKey`, …; pino's `*.x`
matches exactly one level, so `log.info({ token })` would otherwise print it). Request bodies are never logged (Fastify does not log them by default; keep it so).

`main.ts` (≤ 40 lines, sequence only):
1. `const settings = loadSettings(process.env)`; on `SettingsError` print the message to stderr and
   `process.exit(1)`.
2. `const db = createDatabase(settings.database)`.
3. `await migrateToLatest(db)` (§2.9); failure → log `db.migration_failed`, exit 1.
4. `const app = await buildApp({ settings, db, clock: systemClock, readinessChecks: [databaseCheck(db)] })`.
5. `const scheduler = createScheduler({ db, clock, logger: app.log, jobs: [], enabled: settings.jobs.enabled })`;
   `scheduler.start()`.
6. `await app.listen({ port, host })`; log `server.started` with port and version.
7. On `SIGTERM`/`SIGINT` once: log `server.stopping`; `scheduler.stop()`; `await app.close()`;
   `await db.destroy()`; exit 0. A timer of `shutdownTimeoutMs` forces exit 1 if this hangs
   (guidelines 7.1 clean shutdown).

### 2.7 Request context and headers

`registerRequestContext(app, settings)`:
- `onRequest` hook: if `settings.server.requireHttps` and `request.protocol !== 'https'` →
  `AppError('HTTPS_REQUIRED')`. Health routes are exempt (Docker health checks call them over
  plain HTTP inside the network).
- `onSend` hook: header `X-Request-Id: request.id`; header `Cache-Control: no-store` on every
  `/api/` response.

`@fastify/helmet` options: `contentSecurityPolicy: { useDefaults: false, directives: { defaultSrc:
["'none'"], frameAncestors: ["'none'"] } }` (without `useDefaults: false` helmet adds its default
directives), `hsts: { maxAge: 63072000, includeSubDomains: true, preload:
true }`, `referrerPolicy: { policy: 'no-referrer' }`, `crossOriginResourcePolicy: { policy:
'same-origin' }`, `crossOriginOpenerPolicy: false`, `crossOriginEmbedderPolicy: false`
(security.md §3). The numbers go in `constants.ts` (`HSTS_MAX_AGE_SECONDS`).

### 2.8 Rate limits

`http/rate-limits.ts`: `export type RateLimitFor = (group: RateLimitGroup) => RouteOptions['config']`
and `export function createRateLimitFor(settings: Settings): RateLimitFor`, whose result returns
`{ rateLimit: { max, timeWindow } }` from `RATE_LIMITS`, or `{ rateLimit: false }` when
`settings.server.rateLimitsEnabled` is false. `buildApp` calls it once and passes `rateLimitFor` to
every route-registration function in its options object. Services that call
`fastify.createRateLimit` directly (spec 04 `authSignUpOpen`) check
`settings.server.rateLimitsEnabled` themselves. The plugin is registered with
`global: false`, `keyGenerator: rateLimitKey` (`http/rate-limits.ts`): requests with an
`Authorization: Bearer` header are keyed by `'s:' + first 16 hex characters of sha256(token)` (per
session, so per user, D-27, and independent of the auth preHandler's order), all others by
`'ip:' + request.ip` (Fastify's `trustProxy` hop count makes it the client address),
`errorResponseBuilder` throwing `AppError('RATE_LIMITED')`, `addHeaders` with
`x-ratelimit-remaining` and `retry-after` only. Storage is the plugin's in-memory store (one API
instance; a Redis store is a later change if the API scales out, recorded in
`deployment.md` §7 as a limitation).

### 2.8a Review 1 additions (2026-11-12)

1. **Rate-limit keys.** `rateLimitKey` keys a request by session only when its `Authorization`
   header is `Bearer ` + a token matching `SESSION_TOKEN_REGEX` (contract `limits.ts`, added by this
   spec: `SESSION_TOKEN_PATTERN = '^kdk_s_[A-Za-z0-9_-]{43}$'`); everything else by IP. The groups
   in `RATE_LIMIT_IP_KEYED_GROUPS = ['authSignIn', 'authSignUp', 'publicLinkOpen',
   'publicLinkEntries', 'publicLinkContent']` (server `constants.ts`; the groups that do not exist yet
   are listed anyway) are **always** keyed by IP: `createRateLimitFor` returns `{ rateLimit: { max,
   timeWindow, keyGenerator: ipRateLimitKey } }` for them. Otherwise random tokens would give every
   request its own bucket. General rule for every later spec: routes that do not require a session
   always use an IP-keyed group.
2. **Database pool errors.** `createDatabase(url, onPoolError)` adds `pool.on('error', onPoolError)`;
   `main.ts` passes a function logging `LOG_EVENTS.dbPoolError = 'db.pool_error'` (message only)
   through `app.log` (an idle client error must not crash the process).
3. **Framework errors.** Fastify option `frameworkErrors: (error, request, reply) => sendError(reply,
   request.id, 'MALFORMED_REQUEST', null)` (`sendError` exported from `error-handler.ts`), so
   malformed URLs also get `errorResponseSchema`, a `requestId` and `no-store`.
4. **Validation errors.** The error handler maps `hasZodFastifySchemaValidationErrors(error) ||
   error.validation !== undefined` to `VALIDATION_FAILED`.
5. **Settings errors list every problem.** Per-variable rules (including each `TRUSTED_PROXIES`
   entry) are refinements on the variable's own schema, not in the object-level `superRefine`, so a
   missing variable does not hide other invalid ones; only the two cross-variable rules
   (`PUBLIC_BASE_URL` http, `RATE_LIMITS_ENABLED`) stay object-level.
6. `PUBLIC_BASE_URL` must have no user, password, query or fragment (path still forbidden as
   before: origin only, no trailing slash).
7. `no-store` applies to every response whose URL is `/api` or starts with `/api/` (constant
   `API_PATH_PREFIX = '/api/'`).
8. Remaining literals move to `config/constants.ts` (`ID_BYTES`, the `'fatal'` level, the
   `'https'` protocol — reuse `HTTPS_PROTOCOL`); unused text keys `notUrl` and `unknownVariable` are
   removed; the integration global-setup `pg.Client` gets `connectionTimeoutMillis: RETRY_DELAY_MS * 4`.
9. Tests: a random `Bearer x` on `authSignIn`-group route stub shares the IP bucket (11th request →
   429 with `max` 10); `kdk_s_` + 43 chars → session key; `log.info({ token: 'x' })` prints
   `[redacted]`; `GET /api/v1/%E0%A4%A` → 400 `MALFORMED_REQUEST` with `requestId`; an
   error with `validation` set → 400 `VALIDATION_FAILED`; settings with `DATABASE_URL` only and
   `TRUSTED_PROXIES=bad` → the message names `PUBLIC_BASE_URL` **and** `TRUSTED_PROXIES`;
   `PUBLIC_BASE_URL=https://u:p@a.example` invalid; `/api/v2/x` 404 has `no-store`; pool `error`
   event → logged, process alive.

### 2.9 Database

`adapters/db/database.ts`:
- `createDatabase(config): Kysely<Database>` with `PostgresDialect({ pool: new Pool({
  connectionString, max, connectionTimeoutMillis, statement_timeout }) })` and
  `plugins: [new CamelCasePlugin()]`.
- At module load: `types.setTypeParser(INT8_OID, parseSafeInteger)` where `INT8_OID = 20` (in
  `constants.ts`) and `parseSafeInteger` throws `RangeError` for values beyond
  `Number.MAX_SAFE_INTEGER` (sizes and sequence numbers fit easily; overflow must fail loudly).
- `databaseCheck(db): ReadinessCheck` = `sql\`select 1\`.execute(db)` raced against
  `READY_CHECK_TIMEOUT_MS`.

`adapters/db/schema.ts`: the `Database` interface with one table interface per table of
`data-model.md` §2 (`UsersTable`, `SessionsTable`, `InvitesTable`, `CollectionsTable`,
`CollectionSharesTable`, `EntriesTable`, `EntryMembershipsTable`, `MembershipTombstonesTable`,
`MediaTable`, `ObjectsTable`, `PublicLinksTable`), camelCase property names, `bytea` →
`Buffer`, `timestamptz` → `Date`, `bigint` → `number`, nullable columns `T | null`, columns with
defaults wrapped in `Generated<T>`. Enum-like text columns use union types
(`'active' | 'pending_deletion'`), declared once in `domain/enums.ts` and reused.

`adapters/db/migrations/0001_initial.ts`: `export async function up(db: Kysely<unknown>)`
executing the DDL of `data-model.md` §2.1-2.11 **verbatim** (one `sql\`…\`.execute(db)` per
statement, in section order), and `down` dropping all tables in reverse order. Migrations are the
one place where literal numbers in SQL are allowed (spec 01 lint exception).

`adapters/db/migrations/index.ts`: `export const MIGRATIONS: Record<string, Migration> = {
'0001_initial': initial }` (a static map, because the server is bundled and cannot read migration
files from disk).

`adapters/db/migrator.ts`: `migrateToLatest(db)` with Kysely `Migrator` and a provider returning
`MIGRATIONS`; returns the list of applied names; throws on any error result. Kysely's migration
lock makes concurrent boots safe.

### 2.10 Job scheduler

`jobs/scheduler.ts`:

```ts
export type JobDefinition = {
  readonly name: string;
  readonly lockId: number;
  readonly intervalMs: number;
  readonly timeoutMs: number;
  readonly run: (context: JobContext) => Promise<JobResult>;
};
export type JobContext = { readonly db: Kysely<Database>; readonly now: Date; readonly signal: AbortSignal };
export type JobResult = { readonly processed: number; readonly remaining: boolean };
export type SchedulerOptions = {
  readonly db: Kysely<Database>;
  readonly clock: Clock;
  readonly logger: FastifyBaseLogger;
  readonly jobs: readonly JobDefinition[];
  readonly enabled: boolean;
  readonly tryLock?: (lockId: number) => Promise<(() => Promise<void>) | null>;
};
export function createScheduler(options: SchedulerOptions): { start(): void; stop(): Promise<void> };
```

`tryLock` defaults to the advisory-lock implementation below; tests inject a fake. It returns a
release function, or `null` when another instance holds the lock.

- `start()` runs the first tick immediately (`setTimeout(tick, 0)`), then every `JOB_TICK_MS`; every
  job is due on that first tick; on later ticks a job is due when `lastRunAt + intervalMs <= now`.
  Ticks never overlap (a tick that finds the previous one still running is skipped).
- A due job runs only if `select pg_try_advisory_lock(lockId)` returns true on a **dedicated
  connection** (`db.connection().execute(...)`), and that connection unlocks in `finally`. When the
  lock is not acquired (another instance runs it), the scheduler sets that job's `lastRunAt = now`
  anyway, so it is next due one interval later. The advisory lock guarantees a job never runs
  **concurrently** on two instances; it can still run twice in one interval (instance B ticks after
  A finished). Every job must therefore be idempotent and batch-bounded (specs 04-06, 17-18 jobs are).
- `stop()` during a pending `tryLock`: when the lock resolves after `stop()`, release it immediately
  and do not start the job.
- Each run gets an `AbortSignal` that aborts after `timeoutMs`; the run logs `job.started`,
  `job.finished` (with `processed`, `remaining`, duration) or `job.failed` (error message,
  stack). A failure does not stop the scheduler; the next run happens at the next interval
  (guidelines 6: retry policy = next interval; dead-letter = the `job.failed` log line plus the
  rows still matching next time).
- If `remaining` is true, the job is due again on the next tick instead of waiting a full interval
  (drains backlogs in bounded batches).
- `stop()` stops ticking, aborts running jobs, and waits for them to settle.
- `jobs/keys.ts`: `JOB_LOCK_IDS` frozen object; this spec adds none; specs 04-05 add theirs.

### 2.11 CLI (`cli.ts`)

`node dist/cli.js <command> [--flag value]`. A dispatch map `COMMANDS` (guidelines 3). This spec
adds only `migrate-status` (prints applied and pending migration names, exit 0) and
`help`. Unknown command → usage text on stderr, exit code 2. Spec 19 adds the admin commands.

### 2.12 Platform helpers

- `platform/clock.ts`: `export type Clock = { readonly now: () => Date }`; `systemClock`;
  `fixedClock(date)` for tests.
- `platform/ids.ts`: `randomId(): string` = `encodeBytes(randomBytes(16))` (22 chars, matches
  `ID_PATTERN`); `randomToken(bytes: number): string` = `encodeBytes(randomBytes(bytes))`. Both
  use `node:crypto.randomBytes`.

### 2.13 Routes

| Route | Handler | Rate limit |
|---|---|---|
| `GET /health/live` | `{ status: 'ok' }` | none |
| `GET /health/ready` | runs every `ReadinessCheck` in parallel; all ok → `200 { status: 'ok' }`; any failure → `503 { status: 'unavailable', failed: [name…] }` and log `health.not_ready` | none |
| `GET /api/v1/config` | `configController` maps `settings` → `PublicConfigDto` (`apiVersion: 1`; `trashDays` is `TRASH_DAYS_DEFAULT = 30` and `accountDeletionGraceDays` is `ACCOUNT_DELETION_GRACE_DAYS_DEFAULT = 7` from constants until specs 06 and 04 add the `TRASH_DAYS` and `ACCOUNT_DELETION_GRACE_DAYS` settings, which then feed them) | `users` group |

Response schemas are bound for every route (serialization strips unknown fields).

## 3. Test harness

`infra/compose/docker-compose.test.yml`: one service `postgres-test`, image `postgres:17`
(pinned digest recorded in `docs/dependencies.md` §"Container images"), env
`POSTGRES_PASSWORD=test`, `POSTGRES_DB=kodakiko_test`, port `127.0.0.1:55432:5432`, tmpfs data
directory, healthcheck `pg_isready`.

`test/integration/global-setup.ts`: reads `TEST_DATABASE_URL` (default
`postgres://postgres:test@127.0.0.1:55432/kodakiko_test`); fails with a clear message naming the
compose command if it cannot connect within 10 s.

`test/integration/helpers.ts`:
- `createTestDatabase()`: creates a fresh schema-isolated database per test file
  (`CREATE DATABASE kodakiko_test_<random>` from the admin connection; returns a Kysely instance
  and a `drop()`), then runs `migrateToLatest`.
- `testSettings(overrides)`: a valid `Settings` built through `loadSettings` from a fixed env
  object with `REQUIRE_HTTPS=false`.
- `buildTestApp(deps)`: `buildApp` + `app.ready()`; tests use `app.inject()`.

## 4. Tests

Unit (`test/unit/`):
1. `settings.test.ts`: `PUBLIC_BASE_URL=http://…` rejected with `REQUIRE_HTTPS=true`, accepted with
   `false`; `RATE_LIMITS_ENABLED=false` rejected unless `REQUIRE_HTTPS=false` and the host is
   `localhost`/`127.0.0.1`; defaults applied; each required variable missing → `SettingsError` whose
   message names it; `TRUSTED_PROXIES=not-an-ip` invalid (message names the entry); `TRUSTED_PROXIES=` (empty) valid → `[]`; `MAX_MEDIA_PER_ENTRY=101` invalid; the returned
   object is frozen; the error message never contains the value of `DATABASE_URL`.
2. `error-handler.test.ts` (Fastify instance with a test route, no DB): `AppError('NOT_FOUND')` →
   404 with the catalog message and the request id equal to `X-Request-Id`; Zod body violation →
   422 with `issues`; unknown route → 404 shape; thrown plain `Error` → 500 generic message;
   2 MiB + 1 body → 413; invalid JSON → 400.
3. `https.test.ts`: `requireHttps=true` + no forwarded proto → 400 `HTTPS_REQUIRED`; with
   `x-forwarded-proto: https` from a trusted peer (`TRUSTED_PROXIES=loopback`; `app.inject` connects
   from `127.0.0.1`) → passes; the same header with `TRUSTED_PROXIES=` (empty) → 400; `/health/live` exempt.
4. `scheduler.test.ts` with fake timers and a fake lock function: job runs when due, not twice
   concurrently, `remaining: true` reruns next tick, failure logs and continues, `stop()` aborts.
5. `rate-limit.test.ts`: a test route with `max: 2` → third call 429 with `Retry-After`.

Integration (`test/integration/`, real Postgres):
1. `migrations.test.ts`: up creates all 11 tables and the named indexes
   (`objects_one_pending_per_variant`, `objects_one_uploaded_per_variant`,
   `collections_one_home_per_owner`, …); down drops them; up again works. CHECK constraints
   enforced: inserting a user with `kdf_mem_limit_bytes = 1` fails; a 21-char id fails.
2. `health.test.ts`: ready → 200; after `db.destroy()` → 503 with `failed: ['database']`.
3. `config.test.ts`: `GET /api/v1/config` returns exactly the settings-derived DTO; headers include
   `x-request-id`, `cache-control: no-store`, `content-security-policy` exactly
   `default-src 'none';frame-ancestors 'none'` (helmet joins directives with `;` and no space),
   `referrer-policy: no-referrer`.
4. `int8.test.ts`: a `bigint` column round-trips as `number`; a value above
   `Number.MAX_SAFE_INTEGER` read through the pool throws.
5. `scheduler-lock.test.ts`: two schedulers on the same database with one job whose body sleeps
   500 ms → the bodies never overlap in time (advisory lock works); `stop()` while `tryLock` is
   pending → the job body never starts.

## 5. Steps

1. Contract files (§1) + tests.
2. Server package files in §2 order: settings, constants, text, errors, platform, db, migrations,
   migrator, request context, error handling, rate limits, routes, app, scheduler, main, cli,
   build script.
3. `docker compose -f infra/compose/docker-compose.test.yml up -d`.
4. Unit tests, integration tests, `pnpm verify`.
5. `pnpm --filter @kodakiko/server build`; run `node dist/main.js` with a local `.env` (not
   committed) pointing at the test database and `REQUIRE_HTTPS=false`; `curl
   http://127.0.0.1:8080/health/ready` and `/api/v1/config`; stop with Ctrl+C and observe
   `server.stopping` then exit 0.

## 6. Verification checklist

- [ ] `pnpm verify` and `pnpm --filter @kodakiko/server test:integration` pass (paste summaries).
- [ ] The bundled server boots, migrates an empty database, answers both curls, and shuts down
      cleanly on Ctrl+C (paste the three log lines `server.started`, `server.stopping` and the
      exit code).
- [ ] Booting with `DATABASE_URL` unset exits 1 with a message naming `DATABASE_URL` and nothing
      else secret.
- [ ] Log output of one request contains `reqId`, method, URL, status, and no `authorization`
      header value (send one with a dummy token and grep for it: zero hits).
- [ ] `grep -rn "process.env" apps/server/src` → only the two entry points `main.ts` and `cli.ts`
      (each passes it to `loadSettings`).
- [ ] Postgres image digest recorded in `docs/dependencies.md`.
- [ ] `docs/design/data-model.md` DDL and `0001_initial.ts` match statement for statement (report
      any difference instead of silently fixing either).

## 7. Done when

Checklist fully reported and both test suites green.

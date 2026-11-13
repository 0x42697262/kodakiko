# 19 — Admin: users, quotas, invites, stats (API, screens, CLI)

Status: draft 1 (2026-11-11).

Goal: an admin (D-27) can see who uses the server and how much storage each person uses, change
quotas and roles, create and revoke invites, and see server-wide counts — from the app and from the
server CLI — without ever being able to read anyone's content (no key, wrap or blob ever leaves
through these routes).

Read first: `docs/design/api.md` §10; `docs/design/data-model.md` §§2.1-2.3; `docs/design/security.md`
§§5-6; specs 03 (CLI dispatch), 04 (invites, sessions, `createInvite`), 05 (`used_bytes`,
`reconcile-usage`), 07b (client auth), 16 (unlock steps), 18 §2.4 (copy/share mechanism); decisions
D-27, D-47. Depends on 04, 05, 07b, 16, 18. The Settings row that opens the admin screen is
built by spec 20; until then the screen is reachable at `/settings/admin` directly.

## 1. Server

### 1.1 Contract (`admin.ts`, routes)

```ts
const isoTime = z.iso.datetime();
const count = z.number().int().nonnegative();
export const adminUserDto = userDto.extend({ sessionCount: count, lastActiveAt: isoTime.nullable() });
export const adminUserListResponse = z.object({ items: z.array(adminUserDto), nextCursor: z.string().nullable() });
export const adminUserPatchRequest = z.object({ quotaBytes: count.nullable().optional(), role: z.enum(USER_ROLES).optional() }).refine((value) => value.quotaBytes !== undefined || value.role !== undefined);
export const adminInviteCreateRequest = z.object({ role: z.enum(USER_ROLES), expiresInDays: z.number().int().min(1).max(INVITE_MAX_EXPIRY_DAYS) });
export const adminInviteCreateResponse = z.object({ inviteUrl: z.string(), expiresAt: isoTime });
export const inviteDto = z.object({ id: idSchema, role: z.enum(USER_ROLES), createdAt: isoTime, expiresAt: isoTime, redeemedAt: isoTime.nullable(), revokedAt: isoTime.nullable(), redeemedByUsername: z.string().nullable() });
export const inviteListResponse = z.object({ items: z.array(inviteDto) });
export const adminStatsResponse = z.object({ users: count, activeUsers30d: count, pendingDeletion: count, collections: count, entries: count, objects: count, totalBytes: count, quotaBytesAllocated: count.nullable() });
export const ADMIN_REFUSAL_REASONS = ['selfDemotion', 'lastAdmin'] as const;
```

`INVITE_MAX_EXPIRY_DAYS = 90` in contract `limits.ts`. `lastActiveAt` = the newest
`sessions.last_used_at` of the user (null when no session). `quotaBytesAllocated` = sum of
explicit quotas (null when every user uses the default). Routes: `adminUsers()`, `adminUser(id)`,
`adminInvites()`, `adminInvite(id)`, `adminStats()`.

Stats definitions (one SQL round trip): `users` = `count(*) FROM users`; `pendingDeletion` =
users with `status = 'pending_deletion'`; `activeUsers30d` = distinct `user_id` of sessions with
`last_used_at > now − 30 days`; `collections` = all rows of `collections` (every kind); `entries` =
`purpose = 'journal'` rows, trashed included; `objects` = objects in state `uploaded`; `totalBytes`
= `sum(users.used_bytes)` (reservations included, data-model §2.1); `quotaBytesAllocated` =
`sum(quota_bytes)` where not null, `null` when none is set.

### 1.2 Authorization

`requireAdmin` preHandler (after `requireSession`): `request.auth.role !== 'admin'` → `404
NOT_FOUND` (not `403`: members cannot even learn that admin routes exist; guidelines 3.3). Rate
group `admin`.

### 1.3 Service (`services/admin-service.ts`)

| Method | Rules |
|---|---|
| `listUsers(cursor, limit)` | ordered by `username`; cursor = base64url of the last username; `limit` default 50, clamped to `ADMIN_PAGE_MAX = 200`; one query with aggregates (`count(sessions)`, `max(last_used_at)`) — no N+1 |
| `patchUser(actorId, userId, patch)` | target exists else `NOT_FOUND`; `role` change: an admin cannot demote **themselves** (`VALIDATION_FAILED` with `details: { reason: 'selfDemotion' }`) and the server must keep at least one admin (demoting the last one → `VALIDATION_FAILED` with `details: { reason: 'lastAdmin' }`); `quotaBytes`: `null` = server default, an explicit value below the user's current `used_bytes` is allowed (new uploads are refused until usage drops; the response includes the numbers so the UI can warn); log `admin.user_updated` with actor, target, changed fields and old/new values |
| `createInvite(actorId, role, expiresInDays)` | spec 04's `createInvite` with `createdBy = actorId`; log `admin.invite_created` (actor, role, expiry; never the token) |
| `listInvites()` | newest first, at most `ADMIN_INVITES_LIST_MAX = 200`; redeemed ones include `redeemedByUsername` (join); never tokens or hashes |
| `revokeInvite(actorId, id)` | unknown id → `404 NOT_FOUND`; sets `revoked_at` if not redeemed; redeemed or already revoked → `204` unchanged; log `admin.invite_revoked` |
| `stats(now)` | counts in one round trip (`SELECT (SELECT count(*) …), …`); `activeUsers30d` = users with a session used in the last 30 days (`ADMIN_ACTIVE_WINDOW_DAYS = 30`) |

Deleting other users' accounts is **not** an admin feature in v1 (D-27 lists users, storage and
quotas); a user deletes their own account (spec 20). Recorded as a limitation in `security.md` §6.

### 1.4 CLI (`cli.ts` `COMMANDS`)

| Command | Arguments | Output |
|---|---|---|
| `list-users` | `[--json]` | all users (the command pages through `listUsers` until done); table: username, role, status, used / quota (human-readable, IEC units), last active; `--json` prints the `adminUserDto` array |
| `set-quota` | `--username <name> --bytes <n\|default>` | the updated line; same rules as `patchUser` |
| `set-role` | `--username <name> --role <admin\|member>` | same rules (the CLI has no "self"; the last-admin rule applies) |
| `create-invite` | (spec 04) `--role`, `--expires-days` | URL only |
| `list-invites` | `[--json]` | id, role, created, expires, state (open / redeemed by X / revoked / expired) |
| `revoke-invite` | `--id <inviteId>` | `revoked` or `already closed` |
| `stats` | `[--json]` | the stats object |

CLI actions log with `actor: 'cli'`. Byte formatting helper `formatBytesIec` (pure,
`domain/format.ts`): 1024-based, units `B, KiB, MiB, GiB, TiB`, one decimal except bytes. Exact
cases: `0` → `0 B`; `1023` → `1023 B`; `1024` → `1.0 KiB`; `1536` → `1.5 KiB`; `1073741824` →
`1.0 GiB`; `3758096384` → `3.5 GiB`.

### 1.5 Server tests

Unit: `formatBytes` table; last-admin rule; self-demotion rule. Integration: member calling any
admin route → `404` with the generic body; admin flows: list (pagination over 120 users), patch
quota and role (including the two refusals), invite create → list shows it open → revoke → list
shows revoked → sign-up with it fails `INVITE_INVALID`; stats numbers on a seeded database; log
capture contains no token values and no blob values; CLI commands run against the test database
and print the expected lines.

## 2. Client

### 2.1 Admin service (`src/services/admin/admin-service.ts`)

Thin typed wrappers over the five routes (online only), plus `isAdmin()` = kv `account.role ===
'admin'`. `src/services/account/account-service.ts` (new, owned by this spec): `refreshMe()` → `GET
/users/me` → writes kv `account.role`, `account.deletionDueAt` and an in-memory `UserDto` for
settings screens (spec 20 reads it); called as unlock step 5 `refreshMe` (added to spec 16's unlock
table) and on every `lifecycle.subscribeActive(true)`; sign-in and sign-up (07b) also write
`account.role` from their response. Offline → keeps the last values.

### 2.2 Screens (only when `isAdmin()`; the Settings entry is hidden otherwise)

`/settings/admin` with three segments:

- **People**: list rows "username · role · used of quota · last active (relative)"; a `danger`-colored
  "Over quota" tag when `used > (quotaBytes ?? config.defaultQuotaBytes)`; "Pending deletion" tag.
  Tap → sheet with: quota field in GB (binary: value × 1,073,741,824; decimal allowed; or "Use server
  default" toggle), role picker, "Save"; refusals shown inline
  by `details.reason`: `selfDemotion` → "You can't remove your own admin role.", `lastAdmin` → "The
  server needs at least one admin.".
- **Invites**: "New Invite…" (role picker, expiry days 1-90, default 7) → result card with the
  invite URL, "Copy"/"Share…" (same mechanism as spec 18 §2.4) and the note "This link works once.
  Anyone with it can create an account."; the list of invites with state and "Revoke" for open ones.
- **Server**: the stats as labeled numbers; server version and flags from `ConfigService`.

### 2.3 UI byte formatting

`src/features/format/format-bytes.ts` (pure): `formatBytes(n)`, 1024-based (so the binary defaults
and limits show as round numbers) with the familiar labels `B, KB, MB, GB, TB`, one decimal except
bytes and whole numbers shown without `.0`. Exact cases: `0` → `0 B`; `1023` → `1023 B`; `1536` →
`1.5 KB`; `536870912` → `512 MB`; `2147483648` → `2 GB`; `3758096384` → `3.5 GB`.

## 2a. Implementation notes (2026-11-14, Opus)

- Server: `domain/admin.ts` (`formatBytesIec`, `roleChangeRefusal`), `services/admin-service.ts`,
  `http/routes/admin.ts` (with `requireAdmin` = session + role check → `404`). The admin CLI
  commands live in `cli-admin.ts` (`runAdminCommand(name, args, { db, settings, print, logger })`)
  so integration tests run them against a test database; `cli.ts` registers them.
- The user list pages by username with its own text cursor (`decodeTextCursor`) and `COLLATE "C"`
  ordering. The shared cursor helper accepts ids only.
- Over HTTP the `lastAdmin` refusal cannot happen: the acting admin is itself a second admin. It is
  reachable only from the CLI (`actor: 'cli'`), and the CLI test covers it on a fresh database.
- Client: `services/account/account-service.ts` (`refreshMe`, in-memory `user`, `isAdmin`), unlock
  step `refreshMe`, lock step `clearAccount`, and a refresh on every return to the foreground.
  Sign-in and sign-up store `account.role` through `saveAccountData`.
  `services/admin/admin-service.ts` is `createAdminClient`. `formatBytes`
  (`features/format/format-bytes.ts`) replaces spec 18's local `format-size.ts`.
  `CONFIG_FALLBACK` gained `defaultQuotaBytes`.
- `/settings/admin` shows People, Invites and Server. The Settings row comes with spec 20. There
  is no UI flow for this spec in 07c.

## 3. Verification checklist

- [ ] `pnpm verify` and server integration green.
- [ ] As admin in the app: create an invite, open it on another device, sign up; the new user
      appears in People; set their quota to 1 GB; their upload of a larger file ends `dead` with the
      quota message (spec 08) — raise the quota → Retry succeeds.
- [ ] As a member: no Admin entry in Settings; `GET /api/v1/admin/users` with their token → `404`.
- [ ] CLI: `list-users`, `set-quota --bytes default`, `list-invites`, `revoke-invite`, `stats` on
      the production-like compose stack (spec 21).
- [ ] Server logs for the run contain `admin.*` events with actors and no invite tokens.

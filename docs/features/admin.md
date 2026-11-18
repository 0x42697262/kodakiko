# Admin

An admin sees who uses the server and how much storage each person uses, changes quotas and roles,
manages invites and reads server-wide counts, from the app or the server CLI. No admin route ever
returns a key, a wrap or an encrypted blob, so admins cannot read anyone's journal. Code:
`:service:admin`, `:ui:feature:settings` (`AdminScreen`, `AdminViewModel`), and on the server
`apps/server/src/services/admin-service.ts`, `apps/server/src/http/routes/admin.ts`,
`apps/server/src/cli-admin.ts`.

## Who is an admin

The first account comes from an admin invite created with the CLI. Admins can promote others. A
member who calls an admin route gets `404 NOT_FOUND`, so members cannot even tell that the routes
exist. The app learns its role from `GET /users/me` at unlock and on every return to the foreground;
Settings and Admin check the role again on each refresh, so a demoted admin loses the Admin row
("Only admins can open this page.").

## In the app (Settings › Admin)

The Admin row (admin panel icon) shows in Settings only for admins and never in local-only mode.
Three segments:

- **People:** "username · role · used of quota · last active", with **Over quota** and **Pending
  deletion** tags. A tap opens a sheet: quota in GB (1 GB = 1024³ bytes, decimals allowed) or **Use
  server default**, and the role. Refusals: "You can't remove your own admin role." and "The server
  needs at least one admin."
- **Invites:** **New Invite…** (role, expiry 1–90 days, default 7) shows the invite URL (selectable
  text) with **Share…** (the Android share chooser) and "This link works once. Anyone with it can
  create an account." Below, every invite with its state (open, used by X, revoked, expired) and
  Revoke for open ones.
- **Server:** users, users active in the last 30 days, pending deletions, collections, entries
  (trashed included), uploaded objects, total bytes used, quota allocated, server version and flags.

Deleting another person's account is not an admin feature; people delete their own accounts.

## Server CLI

`node --env-file=<env> apps/server/dist/cli.js <command>` (in the production image: `docker compose
exec api node dist/cli.js <command>`):

| Command | Arguments | Prints |
|---|---|---|
| `create-invite` | `--role admin\|member --expires-days <n>` | The invite URL only |
| `list-invites` | `[--json]` | id, role, created, expires, state |
| `revoke-invite` | `--id <inviteId>` | `revoked` or `already closed` |
| `list-users` | `[--json]` | username, role, status, used / quota (KiB, MiB, GiB), last active |
| `set-quota` | `--username <name> --bytes <n\|default>` | The updated line |
| `set-role` | `--username <name> --role admin\|member` | The updated line; the last admin cannot be demoted |
| `stats` | `[--json]` | The server counts |
| `migrate-status` | — | Applied and pending database migrations |
| `apply-bucket-cors` | — | Applies the bucket's CORS rule for the server's public origin, where the link viewer page runs |
| `help` | — | Every command |

CLI actions are logged with `actor: 'cli'`; invite tokens never appear in logs.

## Limits

Lists page by username (50 per page, at most 200); the invite list shows the newest 200.

import type { AuthLogger } from './platform/silent-logger.ts';
import type { Kysely } from 'kysely';
import { USER_ROLES } from '@kodakiko/contract';
import { createFailedSignInTracker } from './adapters/memory/failed-sign-in-tracker.ts';
import type { Database } from './adapters/db/schema.ts';
import { EXIT_CODES, FAILED_SIGN_IN_TRACKER_MAX_ENTRIES, INTEGER_PATTERN, MINUTE_MS } from './config/constants.ts';
import type { Settings } from './config/settings.ts';
import { formatBytesIec } from './domain/admin.ts';
import type { UserRole } from './domain/enums.ts';
import { AppError } from './domain/errors.ts';
import { systemClock } from './platform/clock.ts';
import { toAdminUserDto, toInviteDto } from './http/routes/admin.ts';
import { createAdminService, type AdminService, type AdminUserView, type InviteView } from './services/admin-service.ts';
import { createAuthService } from './services/auth-service.ts';
import { ADMIN_CLI_TEXT } from './text/admin.ts';

export type AdminCliDeps = {
  readonly db: Kysely<Database>;
  readonly settings: Settings;
  readonly print: (line: string) => void;
  readonly logger: AuthLogger;
};

const ACTOR = 'cli';
const JSON_FLAG = '--json';
const FLAG_VALUE_PAIR = 2;
const JSON_INDENT = 2;
const DEFAULT_QUOTA = 'default';
const COLUMN_GAP = '  ';

function flags(args: readonly string[]): Map<string, string> | null {
  if (args.length % FLAG_VALUE_PAIR !== 0) return null;
  const values = new Map<string, string>();
  for (let position = 0; position < args.length; position += FLAG_VALUE_PAIR) {
    const flag = args[position] ?? '';
    const value = args[position + 1];
    if (!flag.startsWith('--') || value === undefined || values.has(flag)) return null;
    values.set(flag, value);
  }
  return values;
}

function userLine(user: AdminUserView): string {
  const quota = user.quotaBytes === null ? ADMIN_CLI_TEXT.defaultQuota : formatBytesIec(user.quotaBytes);
  const lastActive = user.lastActiveAt?.toISOString() ?? ADMIN_CLI_TEXT.never;
  return [user.username, user.role, user.status, `${formatBytesIec(user.usedBytes)} / ${quota}`, lastActive].join(COLUMN_GAP);
}

function inviteState(invite: InviteView, now: Date): string {
  if (invite.revokedAt !== null) return ADMIN_CLI_TEXT.revoked;
  if (invite.redeemedAt !== null) return ADMIN_CLI_TEXT.redeemedBy(invite.redeemedByUsername ?? ADMIN_CLI_TEXT.unknownUser);
  if (invite.expiresAt.getTime() <= now.getTime()) return ADMIN_CLI_TEXT.expired;
  return ADMIN_CLI_TEXT.open;
}

async function allUsers(service: AdminService): Promise<AdminUserView[]> {
  const users: AdminUserView[] = [];
  let cursor: string | undefined;
  for (;;) {
    const page = await service.listUsers(cursor, undefined);
    users.push(...page.items);
    if (page.nextCursor === null) return users;
    cursor = page.nextCursor;
  }
}

export async function runAdminCommand(name: string, args: readonly string[], deps: AdminCliDeps): Promise<number> {
  const authService = createAuthService({
    db: deps.db,
    settings: deps.settings,
    clock: systemClock,
    failedSignIns: createFailedSignInTracker({
      windowMs: deps.settings.auth.failedSignInWindowMinutes * MINUTE_MS,
      maxEntries: FAILED_SIGN_IN_TRACKER_MAX_ENTRIES,
    }),
    logger: deps.logger,
    checkOpenSignUpRate: async () => true,
  });
  const service = createAdminService({ db: deps.db, clock: systemClock, logger: deps.logger, createInvite: authService.createInvite });
  const json = args.includes(JSON_FLAG);
  const rest = args.filter((arg) => arg !== JSON_FLAG);
  const values = flags(rest);
  if (values === null) {
    deps.print(ADMIN_CLI_TEXT.usage);
    return EXIT_CODES.usage;
  }
  try {
    switch (name) {
      case 'list-users': {
        const users = await allUsers(service);
        if (json) deps.print(JSON.stringify(users.map(toAdminUserDto), null, JSON_INDENT));
        else for (const user of users) deps.print(userLine(user));
        return EXIT_CODES.success;
      }
      case 'set-quota': {
        const username = values.get('--username');
        const bytes = values.get('--bytes');
        if (username === undefined || bytes === undefined || (bytes !== DEFAULT_QUOTA && !INTEGER_PATTERN.test(bytes))) {
          deps.print(ADMIN_CLI_TEXT.usage);
          return EXIT_CODES.usage;
        }
        const userId = await service.findUserIdByUsername(username);
        const updated = await service.patchUser(ACTOR, userId, { quotaBytes: bytes === DEFAULT_QUOTA ? null : Number(bytes) });
        deps.print(userLine(updated));
        return EXIT_CODES.success;
      }
      case 'set-role': {
        const username = values.get('--username');
        const role = values.get('--role');
        if (username === undefined || !USER_ROLES.some((known) => known === role)) {
          deps.print(ADMIN_CLI_TEXT.usage);
          return EXIT_CODES.usage;
        }
        const userId = await service.findUserIdByUsername(username);
        deps.print(userLine(await service.patchUser(ACTOR, userId, { role: role as UserRole })));
        return EXIT_CODES.success;
      }
      case 'list-invites': {
        const invites = await service.listInvites();
        if (json) deps.print(JSON.stringify(invites.map(toInviteDto), null, JSON_INDENT));
        else {
          const now = new Date();
          for (const invite of invites) {
            deps.print([invite.id, invite.role, invite.createdAt.toISOString(), invite.expiresAt.toISOString(), inviteState(invite, now)].join(COLUMN_GAP));
          }
        }
        return EXIT_CODES.success;
      }
      case 'revoke-invite': {
        const id = values.get('--id');
        if (id === undefined) {
          deps.print(ADMIN_CLI_TEXT.usage);
          return EXIT_CODES.usage;
        }
        deps.print((await service.revokeInvite(ACTOR, id)) === 'revoked' ? ADMIN_CLI_TEXT.revoked : ADMIN_CLI_TEXT.alreadyClosed);
        return EXIT_CODES.success;
      }
      case 'stats': {
        const stats = await service.stats();
        if (json) deps.print(JSON.stringify(stats, null, JSON_INDENT));
        else for (const [key, value] of Object.entries(stats)) deps.print(`${key}${COLUMN_GAP}${value ?? ADMIN_CLI_TEXT.none}`);
        return EXIT_CODES.success;
      }
      default:
        deps.print(ADMIN_CLI_TEXT.usage);
        return EXIT_CODES.usage;
    }
  } catch (error) {
    if (error instanceof AppError) {
      const details = error.details as { reason?: string } | null;
      deps.print(ADMIN_CLI_TEXT.refused(details?.reason ?? error.code));
      return EXIT_CODES.failure;
    }
    throw error;
  }
}

export const ADMIN_CLI_COMMANDS: readonly string[] = Object.freeze(['list-users', 'set-quota', 'set-role', 'list-invites', 'revoke-invite', 'stats']);

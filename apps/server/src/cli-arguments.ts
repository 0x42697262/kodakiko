import { USER_ROLES } from '@kodakiko/contract';
import {
  CLI_FLAGS,
  CLI_INVITE_EXPIRES_DAYS_MAX,
  CLI_INVITE_EXPIRES_DAYS_MIN,
  INTEGER_PATTERN,
} from './config/constants.ts';
import type { UserRole } from './domain/enums.ts';

export type CreateInviteArguments = {
  readonly role: UserRole;
  readonly expiresInDays: number | null;
};

const FLAG_VALUE_PAIR = 2;

function isRole(value: string | undefined): value is UserRole {
  return USER_ROLES.some((role) => role === value);
}

function parseExpiresDays(value: string | undefined): number | null | undefined {
  if (value === undefined) return null;
  if (!INTEGER_PATTERN.test(value)) return undefined;
  const days = Number(value);
  if (days < CLI_INVITE_EXPIRES_DAYS_MIN || days > CLI_INVITE_EXPIRES_DAYS_MAX) return undefined;
  return days;
}

export function parseCreateInviteArguments(
  args: readonly string[],
): CreateInviteArguments | null {
  if (args.length % FLAG_VALUE_PAIR !== 0) return null;
  const values = new Map<string, string>();
  for (let position = 0; position < args.length; position += FLAG_VALUE_PAIR) {
    const flag = args[position] ?? '';
    const value = args[position + 1];
    const known = flag === CLI_FLAGS.role || flag === CLI_FLAGS.expiresDays;
    if (!known || values.has(flag) || value === undefined) return null;
    values.set(flag, value);
  }
  const role = values.get(CLI_FLAGS.role);
  const expiresInDays = parseExpiresDays(values.get(CLI_FLAGS.expiresDays));
  if (!isRole(role) || expiresInDays === undefined) return null;
  return { role, expiresInDays };
}

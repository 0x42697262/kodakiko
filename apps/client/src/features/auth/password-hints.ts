import { checkPasswordPolicy, normalizeUsername, type PasswordRule } from '@kodakiko/core';

export function passwordHints(
  password: string,
  username: string,
  touched: boolean,
): readonly PasswordRule[] {
  return touched ? checkPasswordPolicy(password, normalizeUsername(username)) : [];
}

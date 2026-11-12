import type { PasswordRule } from '@kodakiko/core';
import { EN, type StaticTextKey } from '../../text/en';
import { AuthError, type AuthFailure } from '../../services/auth-errors';

const KEY_BY_REASON: Readonly<
  Record<Exclude<AuthFailure, 'passwordPolicy' | 'recoveryWordsInvalid'>, StaticTextKey>
> = Object.freeze({
  wrongPassword: 'errors.wrongPassword',
  invalidCredentials: 'errors.invalidCredentials',
  unreachable: 'errors.unreachable',
  notHttps: 'errors.notHttps',
  notKodakiko: 'errors.notKodakiko',
  inviteInvalid: 'errors.inviteInvalid',
  usernameTaken: 'errors.usernameTaken',
  usernameInvalid: 'errors.usernameInvalid',
  unsafeServer: 'errors.unsafeServer',
  kdfUnavailable: 'errors.kdfUnavailable',
  rateLimited: 'errors.rateLimited',
  generic: 'errors.generic',
});

export const PASSWORD_RULE_KEYS: Readonly<Record<PasswordRule, StaticTextKey>> = Object.freeze({
  tooShort: 'password.tooShort',
  equalsUsername: 'password.equalsUsername',
  singleRepeatedCharacter: 'password.singleRepeatedCharacter',
});

export type ErrorBanner = { readonly testID: string; readonly message: string };

export function errorBanner(error: AuthError): ErrorBanner {
  if (error.reason === 'passwordPolicy') {
    const key =
      error.rules[0] === undefined ? 'errors.generic' : PASSWORD_RULE_KEYS[error.rules[0]];
    return { testID: key, message: EN[key] };
  }
  if (error.reason === 'recoveryWordsInvalid') {
    return {
      testID: 'errors.recoveryWordsInvalid',
      message: EN['errors.recoveryWordsInvalid'](error.position),
    };
  }
  const key = KEY_BY_REASON[error.reason];
  return { testID: key, message: EN[key] };
}

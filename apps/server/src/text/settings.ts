export const SETTINGS_TEXT = Object.freeze({
  invalidPrefix: 'Invalid server settings:',
  separator: '; ',
  reasonSeparator: ': ',
  notInteger: 'must be a whole number',
  notBoolean: 'must be exactly "true" or "false"',
  notPostgresUrl: 'must be a postgres:// or postgresql:// URL',
  notHttpUrl:
    'must be an http:// or https:// origin without credentials, path, query, fragment or trailing slash',
  httpNeedsHttpsOff: 'may use http:// only when REQUIRE_HTTPS is false',
  rateLimitsOffLocalOnly:
    'may be false only when REQUIRE_HTTPS is false and PUBLIC_BASE_URL is localhost or 127.0.0.1',
  fakeParamsSecretTooShort: 'must decode to at least 32 bytes',
  invalidProxyEntry: (entry: string) =>
    `invalid entry "${entry}"; use an IP address, a CIDR, or loopback, linklocal, uniquelocal`,
});

export const SECURITY_TEXT = Object.freeze({
  rateLimitsDisabled: 'Rate limits are disabled; this is only for local UI tests.',
});

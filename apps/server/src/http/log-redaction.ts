import type { Settings } from '../config/settings.ts';

const HEADER_PATHS = [
  'req.headers.authorization',
  'req.headers["x-kodakiko-link-token"]',
  'req.headers["x-kodakiko-view-token"]',
  'req.headers.cookie',
];

const SECRET_FIELDS = [
  'entryKeyByCollection',
  'mediaKeyByEntry',
  'keyMaterial',
  'password',
  'loginKey',
  'currentLoginKey',
  'recoveryLoginKey',
  'token',
  'inviteToken',
  'accessToken',
  'masterKeyByKek',
  'masterKeyByRecovery',
  'recoveryKeyByMaster',
  'privateKeyByMaster',
  'keyByOwner',
  'sealedCollectionKey',
];

export const REDACT_PATHS: readonly string[] = Object.freeze([
  ...HEADER_PATHS,
  ...SECRET_FIELDS.map((field) => `*.${field}`),
  ...SECRET_FIELDS,
]);

const REDACT_CENSOR = '[redacted]';

export function loggerOptions(settings: Settings) {
  return {
    level: settings.log.level,
    redact: { paths: [...REDACT_PATHS], censor: REDACT_CENSOR },
  };
}

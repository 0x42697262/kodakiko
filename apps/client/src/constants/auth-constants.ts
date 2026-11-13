import type { PublicConfigDto } from '@kodakiko/contract';

export const RESTORE_TIMEOUT_MS = 5_000;
export const SIGN_OUT_TIMEOUT_MS = 5_000;
export const ACTIVE_ACCOUNT_SCHEMA_VERSION = 1;
export const RECOVERY_CONFIRM_COUNT = 3;
export const RANDOM_INDEX_LIMIT = 256;
export const RANDOM_INDEX_BYTES = 1;
export const RECOVERY_GRID_COLUMNS = 2;
export const DELETION_DATE_LOCALE = 'en-US';
export const DELETION_DATE_OPTIONS: Intl.DateTimeFormatOptions = Object.freeze({
  dateStyle: 'long',
});
export const FIRST_WORD_POSITION = 1;

export type ConfigFallback = Pick<
  PublicConfigDto,
  | 'signupMode'
  | 'maxMediaPerEntry'
  | 'maxUploadBytes'
  | 'trashDays'
  | 'accountDeletionGraceDays'
  | 'sharingEnabled'
  | 'publicLinksEnabled'
  | 'defaultQuotaBytes'
>;

export const CONFIG_FALLBACK: ConfigFallback = Object.freeze({
  signupMode: 'invite',
  maxMediaPerEntry: 20,
  maxUploadBytes: 3_758_096_384,
  trashDays: 30,
  accountDeletionGraceDays: 7,
  sharingEnabled: false,
  publicLinksEnabled: false,
  defaultQuotaBytes: 107_374_182_400,
});
export const FULL_PERCENT = 100;

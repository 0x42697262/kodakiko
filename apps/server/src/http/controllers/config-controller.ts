import type { PublicConfigDto } from '@kodakiko/contract';
import {
  ACCOUNT_DELETION_GRACE_DAYS_DEFAULT,
  PUBLIC_CONFIG_API_VERSION,
  TRASH_DAYS_DEFAULT,
} from '../../config/constants.ts';
import type { Settings } from '../../config/settings.ts';

export function configController(settings: Settings): PublicConfigDto {
  return {
    apiVersion: PUBLIC_CONFIG_API_VERSION,
    signupMode: settings.flags.signupMode,
    sharingEnabled: settings.flags.sharing,
    publicLinksEnabled: settings.flags.publicLinks,
    maxMediaPerEntry: settings.limits.maxMediaPerEntry,
    maxUploadBytes: settings.limits.maxUploadBytes,
    defaultQuotaBytes: settings.limits.defaultQuotaBytes,
    trashDays: TRASH_DAYS_DEFAULT,
    accountDeletionGraceDays: ACCOUNT_DELETION_GRACE_DAYS_DEFAULT,
    serverVersion: settings.server.version,
  };
}

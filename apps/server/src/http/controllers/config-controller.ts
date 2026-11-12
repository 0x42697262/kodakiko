import type { PublicConfigDto } from '@kodakiko/contract';
import { PUBLIC_CONFIG_API_VERSION } from '../../config/constants.ts';
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
    trashDays: settings.entries.trashDays,
    accountDeletionGraceDays: settings.accounts.deletionGraceDays,
    serverVersion: settings.server.version,
  };
}

import { STREAM_CHUNK_SIZE_BASE, STREAM_CHUNK_SIZE_EXPONENT } from '@kodakiko/core';
import { EN } from '../../text/en';
import { formatBytes } from '../format/format-bytes';
import type { DetailRow, DetailSection } from '../viewer/entry-details-model';

export type ServerFactsInput = {
  readonly serverUrl: string | null;
  readonly config: {
    readonly apiVersion: number;
    readonly signupMode: string;
    readonly sharingEnabled: boolean;
    readonly publicLinksEnabled: boolean;
    readonly maxMediaPerEntry: number;
    readonly maxUploadBytes: number;
    readonly defaultQuotaBytes: number;
    readonly trashDays: number;
    readonly accountDeletionGraceDays?: number;
    readonly serverVersion: string;
  } | null;
  readonly user: { readonly usedBytes: number; readonly quotaBytes: number | null } | null;
  readonly account: { readonly userId: string; readonly username: string; readonly opsLimit: number; readonly memLimitBytes: number; readonly recoveryConfirmed: boolean };
  readonly fingerprint: string;
  readonly keyStorage: { readonly persistent: boolean; readonly biometric: boolean };
  readonly encryptThumbnails: boolean;
  readonly local: boolean;
};

const onOff = (value: boolean) => (value ? EN['server.on'] : EN['server.off']);

export function serverFactSections(input: ServerFactsInput): readonly DetailSection[] {
  const sections: DetailSection[] = [];
  if (!input.local && input.config !== null) {
    const config = input.config;
    const rows: DetailRow[] = [
      { label: EN['server.address'], value: input.serverUrl ?? '' },
      { label: EN['server.version'], value: EN['server.versionValue'](config.serverVersion, config.apiVersion) },
      { label: EN['server.signup'], value: config.signupMode === 'invite' ? EN['server.signupInvite'] : EN['server.signupOpen'] },
      { label: EN['server.sharing'], value: onOff(config.sharingEnabled) },
      { label: EN['server.links'], value: onOff(config.publicLinksEnabled) },
      { label: EN['server.maxMedia'], value: String(config.maxMediaPerEntry) },
      { label: EN['server.maxUpload'], value: formatBytes(config.maxUploadBytes) },
      { label: EN['server.trashDays'], value: EN['server.days'](config.trashDays) },
    ];
    if (input.user !== null) {
      rows.push({ label: EN['server.used'], value: EN['server.usedValue'](formatBytes(input.user.usedBytes), formatBytes(input.user.quotaBytes ?? config.defaultQuotaBytes)) });
    }
    sections.push({ title: EN['server.section'], rows });
  }
  const account = input.account;
  const accountRows: DetailRow[] = [
    { label: EN['server.accountId'], value: account.userId },
    { label: EN['server.username'], value: account.username },
  ];
  if (!input.local && input.fingerprint !== '') accountRows.push({ label: EN['server.code'], value: input.fingerprint });
  accountRows.push(
    { label: EN['server.hashing'], value: EN['server.hashingValue'](account.opsLimit, formatBytes(account.memLimitBytes)) },
    { label: EN['server.recovery'], value: account.recoveryConfirmed ? EN['server.recoveryConfirmed'] : EN['server.recoveryNotConfirmed'] },
  );
  sections.push({ title: EN['server.account'], rows: accountRows });
  const keyStorage = input.keyStorage.persistent ? (input.keyStorage.biometric ? EN['server.keyBiometric'] : EN['server.keyKept']) : EN['server.keyNotKept'];
  sections.push({
    title: EN['server.keys'],
    rows: [
      { label: EN['server.keyStorage'], value: keyStorage },
      { label: EN['server.media'], value: EN['server.mediaValue'](formatBytes(STREAM_CHUNK_SIZE_BASE ** STREAM_CHUNK_SIZE_EXPONENT)) },
      { label: EN['server.metadata'], value: EN['server.metadataValue'] },
      { label: EN['server.sharingKeys'], value: EN['server.sharingKeysValue'] },
      { label: EN['server.thumbnails'], value: input.encryptThumbnails ? EN['server.thumbnailsEncrypted'] : EN['server.thumbnailsPlain'] },
    ],
  });
  return sections;
}

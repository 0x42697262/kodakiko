export const STORAGE_KEYS = Object.freeze({
  accountUserId: 'account.userId',
  accountUsername: 'account.username',
  accountKdfParams: 'account.kdfParams',
  accountMasterKeyByKek: 'account.masterKeyByKek',
  accountMasterKeyByRecovery: 'account.masterKeyByRecovery',
  accountMode: 'account.mode',
  accountRecoveryKeyByMaster: 'account.recoveryKeyByMaster',
  accountPublicKey: 'account.publicKey',
  accountPrivateKeyByMaster: 'account.privateKeyByMaster',
  accountRecoveryConfirmed: 'account.recoveryConfirmed',
  accountHomeCollectionId: 'account.homeCollectionId',
  accountRole: 'account.role',
  accountDeletionDueAt: 'account.deletionDueAt',
  deviceStoragePersisted: 'device.storagePersisted',
  deviceNotificationPermissionAsked: 'device.notificationPermissionAsked',
  settingsDevice: 'settings.device',
  diagnosticsProbe: 'diagnostics.probe',
} as const);

export type StorageKey = (typeof STORAGE_KEYS)[keyof typeof STORAGE_KEYS];

export const SECURE_STORE_KEYS = Object.freeze({
  server: 'kodakiko.server',
  masterKeyPrefix: 'kodakiko.mk.',
  masterKeyAuthPrefix: 'kodakiko.mkauth.',
  sessionPrefix: 'kodakiko.session.',
});

export function masterKeyStoreKey(accountHash: string): string {
  return `${SECURE_STORE_KEYS.masterKeyPrefix}${accountHash}`;
}

export function sessionStoreKey(accountHash: string): string {
  return `${SECURE_STORE_KEYS.sessionPrefix}${accountHash}`;
}

export function masterKeyAuthModeStoreKey(accountHash: string): string {
  return `${SECURE_STORE_KEYS.masterKeyAuthPrefix}${accountHash}`;
}

export const KEY_AUTH_MODES = Object.freeze({ biometric: 'biometric', device: 'device' } as const);

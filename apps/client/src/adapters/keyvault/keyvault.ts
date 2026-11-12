import type { Sodium } from '@kodakiko/core';

export interface KeyVault {
  readonly capabilities: () => Promise<{
    readonly persistent: boolean;
    readonly biometric: boolean;
  }>;
  readonly storeMasterKey: (accountKey: string, masterKey: Uint8Array) => Promise<void>;
  readonly loadMasterKey: (accountKey: string, prompt: string) => Promise<Uint8Array | null>;
  readonly forget: (accountKey: string) => Promise<void>;
}

export declare function createKeyVault(deps: { readonly sodium: Sodium }): KeyVault;

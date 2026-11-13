import type { Sodium } from '@kodakiko/core';

export type KeepUnlockedVault = {
  readonly enable: (accountKey: string, masterKey: Uint8Array) => Promise<void>;
  readonly disable: (accountKey: string) => Promise<void>;
};

export interface KeyVault {
  readonly capabilities: (accountKey?: string) => Promise<{
    readonly persistent: boolean;
    readonly biometric: boolean;
  }>;
  readonly storeMasterKey: (accountKey: string, masterKey: Uint8Array) => Promise<void>;
  readonly loadMasterKey: (accountKey: string, prompt: string) => Promise<Uint8Array | null>;
  readonly forget: (accountKey: string) => Promise<void>;
  readonly keepUnlocked?: KeepUnlockedVault;
}

export declare function createKeyVault(deps: { readonly sodium: Sodium }): KeyVault;

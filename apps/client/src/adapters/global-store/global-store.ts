export interface GlobalStore {
  readonly get: (key: string) => string | null;
  readonly set: (key: string, value: string) => void;
  readonly delete: (key: string) => Promise<void>;
}

export declare function createGlobalStore(): GlobalStore;

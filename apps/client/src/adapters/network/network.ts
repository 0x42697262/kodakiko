export interface NetworkInfo {
  readonly isWifi: () => Promise<boolean>;
  readonly subscribe: (listener: () => void) => () => void;
}

export declare function createNetworkInfo(): NetworkInfo;

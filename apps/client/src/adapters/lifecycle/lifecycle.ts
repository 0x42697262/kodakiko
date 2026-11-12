export interface Lifecycle {
  readonly afterInteractions: () => Promise<void>;
  readonly subscribeActive: (listener: (active: boolean) => void) => () => void;
  readonly subscribeOnline: (listener: () => void) => () => void;
}

export declare function createLifecycle(): Lifecycle;

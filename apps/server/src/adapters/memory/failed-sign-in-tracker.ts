export type FailedSignInTracker = {
  failureCount(key: string, now: Date): number;
  recordFailure(key: string, now: Date): void;
  clear(key: string): void;
};

type FailureWindow = { readonly windowStart: number; readonly count: number };

export function createFailedSignInTracker(options: {
  readonly windowMs: number;
  readonly maxEntries: number;
}): FailedSignInTracker {
  const windows = new Map<string, FailureWindow>();

  function current(key: string, now: Date): FailureWindow | undefined {
    const window = windows.get(key);
    if (window === undefined) return undefined;
    if (now.getTime() - window.windowStart >= options.windowMs) {
      windows.delete(key);
      return undefined;
    }
    return window;
  }

  function evictOldest(): void {
    while (windows.size > options.maxEntries) {
      const oldest = windows.keys().next();
      if (oldest.done === true) return;
      windows.delete(oldest.value);
    }
  }

  return {
    failureCount: (key, now) => current(key, now)?.count ?? 0,
    recordFailure(key, now) {
      const window = current(key, now);
      windows.delete(key);
      windows.set(key, {
        windowStart: window?.windowStart ?? now.getTime(),
        count: (window?.count ?? 0) + 1,
      });
      evictOldest();
    },
    clear: (key) => {
      windows.delete(key);
    },
  };
}

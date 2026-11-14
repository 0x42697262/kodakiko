export const NAVIGATION_LOCK_MS = 600;

export function createNavigationGuard(now: () => number) {
  let lastKey: string | null = null;
  let lastAllowed = Number.NEGATIVE_INFINITY;
  return {
    allow: (key: string): boolean => {
      const current = now();
      if (key === lastKey && current - lastAllowed < NAVIGATION_LOCK_MS) return false;
      lastKey = key;
      lastAllowed = current;
      return true;
    },
  };
}

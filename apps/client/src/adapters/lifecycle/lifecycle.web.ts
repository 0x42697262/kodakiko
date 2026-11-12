import type { Lifecycle } from './lifecycle';

const VISIBLE_STATE = 'visible';
const VISIBILITY_EVENT = 'visibilitychange';
const ONLINE_EVENT = 'online';

function nextFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()));
}

export function createLifecycle(): Lifecycle {
  return {
    afterInteractions: async () => {
      await nextFrame();
      await nextFrame();
    },
    subscribeActive: (listener) => {
      const handler = () => listener(document.visibilityState === VISIBLE_STATE);
      document.addEventListener(VISIBILITY_EVENT, handler);
      return () => document.removeEventListener(VISIBILITY_EVENT, handler);
    },
    subscribeOnline: (listener) => {
      window.addEventListener(ONLINE_EVENT, listener);
      return () => window.removeEventListener(ONLINE_EVENT, listener);
    },
  };
}

import { ACTIVITY_THROTTLE_MS } from '../../services/lock/constants';
import type { ActivitySource } from '../../services/lock/auto-lock';
import type { PageVisibility } from './activity';

const ACTIVITY_EVENTS = Object.freeze(['pointerdown', 'keydown', 'wheel', 'touchstart'] as const);

export function createActivitySource(now: () => number): ActivitySource {
  let last = now();
  function record(): void {
    const current = now();
    if (current - last >= ACTIVITY_THROTTLE_MS) last = current;
  }
  return {
    lastActivityAt: () => last,
    touch: () => {
      last = now();
    },
    start: () => {
      for (const name of ACTIVITY_EVENTS) document.addEventListener(name, record, { passive: true });
    },
    stop: () => {
      for (const name of ACTIVITY_EVENTS) document.removeEventListener(name, record);
    },
  };
}

export const pageVisibility: PageVisibility = {
  onVisible: (listener) => {
    const handler = () => {
      if (document.visibilityState === 'visible') listener();
    };
    document.addEventListener('visibilitychange', handler);
    return () => document.removeEventListener('visibilitychange', handler);
  },
};

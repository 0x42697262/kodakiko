import type { ActivitySource } from '../../services/lock/auto-lock';
import type { PageVisibility } from './activity';

export function createActivitySource(now: () => number): ActivitySource {
  let last = now();
  return {
    lastActivityAt: () => last,
    touch: () => {
      last = now();
    },
    start: () => undefined,
    stop: () => undefined,
  };
}

export const pageVisibility: PageVisibility = { onVisible: () => () => undefined };

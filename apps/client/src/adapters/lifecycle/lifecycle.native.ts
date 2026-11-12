import { AppState } from 'react-native';
import type { Lifecycle } from './lifecycle';

const ACTIVE_STATE = 'active';

export function createLifecycle(): Lifecycle {
  return {
    afterInteractions: () =>
      new Promise((resolve) => {
        requestIdleCallback(() => resolve());
      }),
    subscribeActive: (listener) => {
      const subscription = AppState.addEventListener('change', (state) =>
        listener(state === ACTIVE_STATE),
      );
      return () => subscription.remove();
    },
    subscribeOnline: () => () => undefined,
  };
}

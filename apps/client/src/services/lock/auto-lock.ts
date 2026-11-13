import type { Clock } from '../../adapters/clock';
import type { Lifecycle } from '../../adapters/lifecycle/lifecycle';
import type { AccountStateStore } from '../../composition/account-state';
import { IDLE_CHECK_INTERVAL_MS, MS_PER_MINUTE } from './constants';
import type { LockService } from './lock-service';

export type ActivitySource = {
  readonly lastActivityAt: () => number;
  readonly touch: () => void;
  readonly start: () => void;
  readonly stop: () => void;
};

export type AutoLockMode = 'background' | 'idle';

export type AutoLockDeps = {
  readonly mode: AutoLockMode;
  readonly lifecycle: Lifecycle;
  readonly clock: Clock;
  readonly settings: { readonly get: () => { readonly autoLockMinutes: number } };
  readonly lockService: Pick<LockService, 'lock'>;
  readonly accountState: AccountStateStore;
  readonly keepUnlocked: () => boolean;
  readonly activity: ActivitySource;
  readonly onVisible: (listener: () => void) => () => void;
};

export type AutoLock = {
  readonly start: () => void;
  readonly stop: () => void;
  readonly pause: () => void;
  readonly resume: () => void;
};

export function createAutoLock(deps: AutoLockDeps): AutoLock {
  let paused = false;
  let resumedAt = 0;
  let backgroundedAt: number | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let interval: ReturnType<typeof setInterval> | null = null;
  const unsubscribers: (() => void)[] = [];

  function timeoutMs(): number {
    return deps.settings.get().autoLockMinutes * MS_PER_MINUTE;
  }

  function active(): boolean {
    return !paused && deps.accountState.get().kind === 'unlocked';
  }

  function clearTimer(): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  }

  function lockFor(reason: 'background' | 'idle'): void {
    deps.lockService.lock(reason).catch(() => undefined);
  }

  function onActiveChange(isActive: boolean): void {
    if (!isActive) {
      backgroundedAt = deps.clock.now();
      clearTimer();
      timer = setTimeout(() => {
        if (active()) lockFor('background');
      }, timeoutMs());
      return;
    }
    clearTimer();
    const since = backgroundedAt;
    backgroundedAt = null;
    if (since !== null && active() && deps.clock.now() - Math.max(since, resumedAt) >= timeoutMs()) lockFor('background');
  }

  function checkIdle(): void {
    if (!active() || deps.keepUnlocked()) return;
    const reference = Math.max(deps.activity.lastActivityAt(), resumedAt);
    if (deps.clock.now() - reference >= timeoutMs()) lockFor('idle');
  }

  function start(): void {
    stop();
    resumedAt = deps.clock.now();
    if (deps.mode === 'background') {
      unsubscribers.push(deps.lifecycle.subscribeActive(onActiveChange));
      return;
    }
    deps.activity.start();
    deps.activity.touch();
    interval = setInterval(checkIdle, IDLE_CHECK_INTERVAL_MS);
    unsubscribers.push(deps.onVisible(checkIdle));
  }

  function stop(): void {
    clearTimer();
    if (interval !== null) clearInterval(interval);
    interval = null;
    backgroundedAt = null;
    for (const unsubscribe of unsubscribers.splice(0)) unsubscribe();
    if (deps.mode === 'idle') deps.activity.stop();
  }

  return {
    start,
    stop,
    pause: () => {
      paused = true;
    },
    resume: () => {
      paused = false;
      resumedAt = deps.clock.now();
    },
  };
}

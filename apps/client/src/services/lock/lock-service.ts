import type { AccountStateStore } from '../../composition/account-state';
import { LOCK_LOG_EVENTS } from './constants';

export type LockReason = 'manual' | 'idle' | 'background' | 'signOut' | 'sessionInvalid';
export type LifecycleStep = { readonly name: string; readonly run: () => Promise<void> | void };

export type LockService = {
  readonly lock: (reason: LockReason) => Promise<void>;
  readonly afterUnlock: () => Promise<void>;
  readonly isLocking: () => boolean;
  readonly bind: () => () => void;
  readonly idle: () => Promise<void>;
  readonly lockCount: () => number;
};

export type LockServiceDeps = {
  readonly lockSteps: readonly LifecycleStep[];
  readonly unlockSteps: readonly LifecycleStep[];
  readonly accountState: AccountStateStore;
  readonly log: (event: string, data: Record<string, unknown>) => void;
};

export function createLockService(deps: LockServiceDeps): LockService {
  let locking = false;
  let locks = 0;
  let running: Promise<void> = Promise.resolve();

  async function runSteps(steps: readonly LifecycleStep[], failureEvent: string): Promise<void> {
    for (const step of steps) {
      try {
        await step.run();
      } catch {
        deps.log(failureEvent, { step: step.name });
      }
    }
  }

  function runLockSteps(beforeSteps: () => void): Promise<void> {
    locking = true;
    running = (async () => {
      try {
        beforeSteps();
        await runSteps(deps.lockSteps, LOCK_LOG_EVENTS.stepFailed);
      } finally {
        locking = false;
      }
    })();
    return running;
  }

  async function lock(_reason: LockReason): Promise<void> {
    if (locking || deps.accountState.get().kind !== 'unlocked') return;
    locks += 1;
    await runLockSteps(() => deps.accountState.set({ kind: 'locked' }));
  }

  async function afterUnlock(): Promise<void> {
    await running;
    running = runSteps(deps.unlockSteps, LOCK_LOG_EVENTS.unlockStepFailed);
    await running;
  }

  function bind(): () => void {
    let wasUnlocked = deps.accountState.get().kind === 'unlocked';
    return deps.accountState.subscribe(() => {
      const unlocked = deps.accountState.get().kind === 'unlocked';
      if (wasUnlocked && !unlocked && !locking) runLockSteps(() => undefined).catch(() => undefined);
      if (!wasUnlocked && unlocked) afterUnlock().catch(() => undefined);
      wasUnlocked = unlocked;
    });
  }

  return { lock, afterUnlock, isLocking: () => locking, bind, idle: () => running, lockCount: () => locks };
}

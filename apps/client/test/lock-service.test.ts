import { describe, expect, it } from 'vitest';
import { createAccountState, type AccountState } from '../src/composition/account-state';
import { createLockService, type LifecycleStep } from '../src/services/lock/lock-service';

const LOCK_STEP_NAMES = [
  'cancelExport',
  'stopSync',
  'cancelDownloads',
  'releaseVideo',
  'releaseOriginals',
  'clearThumbnails',
  'clearTray',
  'wipeTemp',
  'clearIndex',
  'clearUiState',
  'clearCollectionNames',
  'wipeKeys',
] as const;

const UNLOCK_STEP_NAMES = ['startSync', 'buildIndex', 'sweepOrphans', 'enforceCache'] as const;

function unlocked(): AccountState {
  return {
    kind: 'unlocked',
    keys: {
      masterKey: new Uint8Array(32).fill(1),
      privateKey: new Uint8Array(32).fill(2),
      publicKey: new Uint8Array(32).fill(3),
    },
    userId: 'user-1',
    username: 'ada',
    recoveryConfirmed: true,
    homeCollectionId: null,
  } as AccountState;
}

function recordingSteps(names: readonly string[], calls: string[], options: { failing?: ReadonlySet<string>; onRun?: (name: string) => void } = {}) {
  return names.map<LifecycleStep>((name) => ({
    name,
    run: async () => {
      options.onRun?.(name);
      calls.push(name);
      if (options.failing?.has(name)) throw new Error(`${name} failed`);
    },
  }));
}

describe('lock service', () => {
  it('sets the state to locked first, runs every step in order even when some throw, and wipes keys last', async () => {
    const accountState = createAccountState(unlocked());
    const calls: string[] = [];
    const statesAtStep: string[] = [];
    const logged: string[] = [];
    const service = createLockService({
      lockSteps: recordingSteps(LOCK_STEP_NAMES, calls, {
        failing: new Set(['releaseVideo', 'wipeTemp']),
        onRun: () => statesAtStep.push(accountState.get().kind),
      }),
      unlockSteps: [],
      accountState,
      log: (event, data) => logged.push(`${event}:${String(data.step)}`),
    });
    await service.lock('manual');
    expect(calls).toEqual([...LOCK_STEP_NAMES]);
    expect(calls.at(-1)).toBe('wipeKeys');
    expect(new Set(statesAtStep)).toEqual(new Set(['locked']));
    expect(logged).toEqual(['lock.step_failed:releaseVideo', 'lock.step_failed:wipeTemp']);
    expect(accountState.get().kind).toBe('locked');
  });

  it('ignores a second lock while the first is running and a lock while already locked', async () => {
    const accountState = createAccountState(unlocked());
    const calls: string[] = [];
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const steps: LifecycleStep[] = [
      { name: 'slow', run: () => gate },
      ...recordingSteps(['wipeKeys'], calls),
    ];
    const service = createLockService({ lockSteps: steps, unlockSteps: [], accountState, log: () => undefined });
    const first = service.lock('idle');
    expect(service.isLocking()).toBe(true);
    await service.lock('manual');
    release();
    await first;
    await service.lock('manual');
    expect(calls).toEqual(['wipeKeys']);
    expect(service.isLocking()).toBe(false);
  });

  it('runs the lock steps once when the state leaves unlocked some other way', async () => {
    const accountState = createAccountState(unlocked());
    const calls: string[] = [];
    const service = createLockService({
      lockSteps: recordingSteps(['stopSync', 'wipeKeys'], calls),
      unlockSteps: [],
      accountState,
      log: () => undefined,
    });
    service.bind();
    accountState.set({ kind: 'signedOut' });
    await service.idle();
    expect(calls).toEqual(['stopSync', 'wipeKeys']);
    expect(accountState.get().kind).toBe('signedOut');
  });

  it('afterUnlock runs the unlock steps in order and a failure does not stop the rest', async () => {
    const accountState = createAccountState(unlocked());
    const calls: string[] = [];
    const service = createLockService({
      lockSteps: [],
      unlockSteps: recordingSteps(UNLOCK_STEP_NAMES, calls, { failing: new Set(['buildIndex']) }),
      accountState,
      log: () => undefined,
    });
    await service.afterUnlock();
    expect(calls).toEqual([...UNLOCK_STEP_NAMES]);
  });
});

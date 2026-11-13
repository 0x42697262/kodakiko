import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Lifecycle } from '../src/adapters/lifecycle/lifecycle';
import { createAccountState, type AccountState } from '../src/composition/account-state';
import { createAutoLock, type ActivitySource } from '../src/services/lock/auto-lock';
import { ACTIVITY_THROTTLE_MS, IDLE_CHECK_INTERVAL_MS } from '../src/services/lock/constants';
import type { LockReason } from '../src/services/lock/lock-service';

const MINUTE = 60_000;
const START = Date.parse('2026-06-01T10:00:00.000Z');

function unlockedState(): AccountState {
  return { kind: 'unlocked', keys: {}, userId: 'u', username: 'ada', recoveryConfirmed: true, homeCollectionId: null } as unknown as AccountState;
}

function setup(mode: 'background' | 'idle', options: { keepUnlocked?: boolean } = {}) {
  const activeListeners = new Set<(active: boolean) => void>();
  const lifecycle: Lifecycle = {
    afterInteractions: async () => undefined,
    subscribeActive: (listener) => {
      activeListeners.add(listener);
      return () => activeListeners.delete(listener);
    },
    subscribeOnline: () => () => undefined,
  };
  const accountState = createAccountState(unlockedState());
  const locks: LockReason[] = [];
  let last = Date.now();
  const activity: ActivitySource = {
    lastActivityAt: () => last,
    touch: () => {
      last = Date.now();
    },
    start: () => undefined,
    stop: () => undefined,
  };
  const visibility = new Set<() => void>();
  const autoLock = createAutoLock({
    mode,
    lifecycle,
    clock: { now: () => Date.now(), nowIso: () => new Date().toISOString() },
    settings: { get: () => ({ autoLockMinutes: mode === 'background' ? 5 : 15 }) },
    lockService: {
      lock: async (reason) => {
        locks.push(reason);
        accountState.set({ kind: 'locked' });
      },
    },
    accountState,
    keepUnlocked: () => options.keepUnlocked ?? false,
    activity,
    onVisible: (listener) => {
      visibility.add(listener);
      return () => visibility.delete(listener);
    },
  });
  autoLock.start();
  return {
    locks,
    activity,
    autoLock,
    setActive: (active: boolean) => {
      for (const listener of activeListeners) listener(active);
    },
    becomeVisible: () => {
      for (const listener of visibility) listener();
    },
  };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(START);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('auto-lock on Android (background)', () => {
  it('does not lock when the app returns before the timeout', () => {
    const { locks, setActive } = setup('background');
    setActive(false);
    vi.setSystemTime(START + 5 * MINUTE - 1_000);
    setActive(true);
    expect(locks).toEqual([]);
  });

  it('locks before the active listener returns when the timeout has passed', () => {
    const { locks, setActive } = setup('background');
    setActive(false);
    vi.setSystemTime(START + 5 * MINUTE);
    setActive(true);
    expect(locks).toEqual(['background']);
  });

  it('locks when the timer fires while still in the background', () => {
    const { locks, setActive } = setup('background');
    setActive(false);
    vi.advanceTimersByTime(5 * MINUTE);
    expect(locks).toEqual(['background']);
  });

  it('ignores idle time on Android', () => {
    const { locks } = setup('background');
    vi.advanceTimersByTime(60 * MINUTE);
    expect(locks).toEqual([]);
  });
});

describe('auto-lock on the web (idle)', () => {
  it('locks only once the idle time reaches the timeout', () => {
    const { locks } = setup('idle');
    vi.setSystemTime(START + 15 * MINUTE - 1_000);
    vi.advanceTimersByTime(IDLE_CHECK_INTERVAL_MS - 1);
    expect(locks).toEqual([]);
    vi.advanceTimersByTime(IDLE_CHECK_INTERVAL_MS);
    expect(locks).toEqual(['idle']);
  });

  it('checks when the page becomes visible', () => {
    const { locks, becomeVisible } = setup('idle');
    vi.setSystemTime(START + 15 * MINUTE);
    becomeVisible();
    expect(locks).toEqual(['idle']);
  });

  it('never locks with keep-unlocked on', () => {
    const { locks, becomeVisible } = setup('idle', { keepUnlocked: true });
    vi.setSystemTime(START + 120 * MINUTE);
    becomeVisible();
    vi.advanceTimersByTime(IDLE_CHECK_INTERVAL_MS * 4);
    expect(locks).toEqual([]);
  });

  it('activity touches from the video player keep it unlocked', () => {
    const { locks, activity } = setup('idle');
    for (let elapsed = 0; elapsed < 20 * MINUTE; elapsed += ACTIVITY_THROTTLE_MS) {
      vi.advanceTimersByTime(ACTIVITY_THROTTLE_MS);
      activity.touch();
    }
    expect(locks).toEqual([]);
  });

  it('pause suspends the checks and resume restarts the clock', () => {
    const { locks, autoLock, becomeVisible } = setup('idle');
    autoLock.pause();
    vi.advanceTimersByTime(30 * MINUTE);
    becomeVisible();
    expect(locks).toEqual([]);
    autoLock.resume();
    vi.advanceTimersByTime(14 * MINUTE);
    expect(locks).toEqual([]);
  });
});

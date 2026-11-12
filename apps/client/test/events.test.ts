import { describe, expect, it } from 'vitest';
import { bindAccountLifecycle } from '../src/composition/account-lifecycle';
import { createAccountState } from '../src/composition/account-state';
import { createEventBus } from '../src/composition/events';

describe('event bus', () => {
  it('delivers typed payloads to subscribers of that event only', () => {
    const bus = createEventBus<{ entriesChanged: readonly string[]; other: number }>();
    const seen: Array<readonly string[]> = [];
    const others: number[] = [];
    bus.on('entriesChanged', (ids) => seen.push(ids));
    bus.on('other', (value) => others.push(value));
    bus.emit('entriesChanged', ['a', 'b']);
    expect(seen).toEqual([['a', 'b']]);
    expect(others).toEqual([]);
  });

  it('stops delivering after unsubscribe', () => {
    const bus = createEventBus<{ entriesChanged: readonly string[] }>();
    const seen: Array<readonly string[]> = [];
    const off = bus.on('entriesChanged', (ids) => seen.push(ids));
    off();
    bus.emit('entriesChanged', ['a']);
    expect(seen).toEqual([]);
  });

  it('keeps delivering when a listener unsubscribes during emit', () => {
    const bus = createEventBus<{ tick: number }>();
    const seen: string[] = [];
    const first = bus.on('tick', () => {
      seen.push('first');
      first();
    });
    bus.on('tick', () => seen.push('second'));
    bus.emit('tick', 1);
    bus.emit('tick', 2);
    expect(seen).toEqual(['first', 'second', 'second']);
  });
});

describe('bindAccountLifecycle', () => {
  const keys = { masterKey: new Uint8Array(32), privateKey: new Uint8Array(32), publicKey: new Uint8Array(32) };

  it('calls onUnlocked when the account becomes unlocked and onLeft when it stops being unlocked', () => {
    const accountState = createAccountState({ kind: 'locked' });
    const calls: string[] = [];
    bindAccountLifecycle(accountState, {
      onUnlocked: () => calls.push('unlocked'),
      onLeftUnlocked: () => calls.push('left'),
    });
    accountState.set({ kind: 'unlocked', keys, userId: 'u', username: 'n' });
    accountState.set({ kind: 'locked' });
    accountState.set({ kind: 'signedOut' });
    expect(calls).toEqual(['unlocked', 'left']);
  });

  it('treats an unlocked state with new keys as still unlocked', () => {
    const accountState = createAccountState({ kind: 'unlocked', keys, userId: 'u', username: 'n' });
    const calls: string[] = [];
    bindAccountLifecycle(accountState, {
      onUnlocked: () => calls.push('unlocked'),
      onLeftUnlocked: () => calls.push('left'),
    });
    expect(calls).toEqual(['unlocked']);
    accountState.set({ kind: 'unlocked', keys, userId: 'u', username: 'n' });
    expect(calls).toEqual(['unlocked']);
  });

  it('stops listening after the returned unbind', () => {
    const accountState = createAccountState({ kind: 'locked' });
    const calls: string[] = [];
    const unbind = bindAccountLifecycle(accountState, {
      onUnlocked: () => calls.push('unlocked'),
      onLeftUnlocked: () => calls.push('left'),
    });
    unbind();
    accountState.set({ kind: 'unlocked', keys, userId: 'u', username: 'n' });
    expect(calls).toEqual([]);
  });
});

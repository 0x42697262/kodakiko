import { describe, expect, it, vi } from 'vitest';
import { createAccountState } from '../src/composition/account-state';

describe('account state', () => {
  it('starts with the given state and notifies subscribers on change', () => {
    const state = createAccountState({ kind: 'signedOut' });
    const listener = vi.fn();
    const unsubscribe = state.subscribe(listener);
    expect(state.get()).toEqual({ kind: 'signedOut' });
    state.set({ kind: 'locked' });
    expect(state.get()).toEqual({ kind: 'locked' });
    expect(listener).toHaveBeenCalledTimes(1);
    unsubscribe();
    state.set({ kind: 'pendingDeletion' });
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

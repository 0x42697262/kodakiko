import { describe, expect, it, vi } from 'vitest';
import { pullToSyncEnabled, runPullToSync } from '../src/features/sync/pull-to-sync';

describe('pull to sync', () => {
  it('is available only for server accounts with sync running', () => {
    expect(pullToSyncEnabled('server', false)).toBe(true);
    expect(pullToSyncEnabled('local', false)).toBe(false);
    expect(pullToSyncEnabled('server', true)).toBe(false);
  });

  it('shows the spinner until the sync settles, even when it fails', async () => {
    const states: boolean[] = [];
    const tick = vi.fn();
    await runPullToSync(() => Promise.reject(new Error('offline')), (value) => states.push(value), tick);
    expect(states).toEqual([true, false]);
    expect(tick).toHaveBeenCalledWith('refresh');
  });
});

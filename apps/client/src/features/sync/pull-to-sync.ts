import type { AccountMode } from '../../constants/auth-constants';
import type { HapticKind } from '../../ui/haptics';

export function pullToSyncEnabled(mode: AccountMode, paused: boolean): boolean {
  return mode === 'server' && !paused;
}

export async function runPullToSync(
  sync: () => Promise<unknown>,
  setRefreshing: (refreshing: boolean) => void,
  tick: (kind: HapticKind) => void,
): Promise<void> {
  tick('refresh');
  setRefreshing(true);
  try {
    await sync();
  } catch {
    return;
  } finally {
    setRefreshing(false);
  }
}

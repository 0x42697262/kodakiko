import type { AccountStateStore } from './account-state';

export type AccountLifecycleHandlers = {
  readonly onUnlocked: () => void;
  readonly onLeftUnlocked: () => void;
};

export function bindAccountLifecycle(
  accountState: AccountStateStore,
  handlers: AccountLifecycleHandlers,
): () => void {
  let wasUnlocked = false;
  function apply(): void {
    const unlocked = accountState.get().kind === 'unlocked';
    if (unlocked && !wasUnlocked) handlers.onUnlocked();
    if (!unlocked && wasUnlocked) handlers.onLeftUnlocked();
    wasUnlocked = unlocked;
  }
  apply();
  return accountState.subscribe(apply);
}

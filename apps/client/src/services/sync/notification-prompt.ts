import { z } from 'zod';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import { createObservable, type Observable } from '../../composition/account-state';
import { STORAGE_KEYS } from '../../keys/storage-keys';

export type NotificationPrompt = {
  readonly ensureAsked: () => Promise<void>;
  readonly visible: Observable<boolean>;
};

export type NotificationPromptDeps = {
  readonly rowStore: RowStore;
  readonly request: (() => Promise<boolean>) | null;
};

export function createNotificationPrompt(deps: NotificationPromptDeps): NotificationPrompt {
  const visible = createObservable(false);
  let pending: Promise<void> | null = null;

  async function ask(request: () => Promise<boolean>): Promise<void> {
    const answered = await deps.rowStore.kvGetJson(
      STORAGE_KEYS.deviceNotificationPermissionAsked,
      z.boolean(),
    );
    if (answered !== null) return;
    visible.set(true);
    try {
      const granted = await request();
      await deps.rowStore.kvSetJson(STORAGE_KEYS.deviceNotificationPermissionAsked, granted);
    } finally {
      visible.set(false);
    }
  }

  function ensureAsked(): Promise<void> {
    if (deps.request === null) return Promise.resolve();
    pending ??= ask(deps.request).finally(() => {
      pending = null;
    });
    return pending;
  }

  return { ensureAsked, visible };
}

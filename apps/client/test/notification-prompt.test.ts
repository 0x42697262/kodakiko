import { beforeEach, describe, expect, it } from 'vitest';
import { z } from 'zod';
import { createNotificationPrompt } from '../src/services/sync/notification-prompt';
import { STORAGE_KEYS } from '../src/keys/storage-keys';
import { createMemoryRowStore } from './support';

let rowStore: ReturnType<typeof createMemoryRowStore>;

beforeEach(async () => {
  rowStore = createMemoryRowStore();
  await rowStore.open('acct');
});

describe('notification prompt', () => {
  it('asks once, shows the banner while asking and stores the answer', async () => {
    const visibility: boolean[] = [];
    let release: (granted: boolean) => void = () => undefined;
    let requests = 0;
    const prompt = createNotificationPrompt({
      rowStore,
      request: () => {
        requests += 1;
        return new Promise<boolean>((resolve) => {
          release = resolve;
        });
      },
    });
    prompt.visible.subscribe(() => visibility.push(prompt.visible.get()));
    const first = prompt.ensureAsked();
    await new Promise((resolve) => setTimeout(resolve, 1));
    expect(prompt.visible.get()).toBe(true);
    release(false);
    await first;
    expect(prompt.visible.get()).toBe(false);
    await prompt.ensureAsked();
    expect(requests).toBe(1);
    expect(await rowStore.kvGetJson(STORAGE_KEYS.deviceNotificationPermissionAsked, z.boolean())).toBe(false);
    expect(visibility).toEqual([true, false]);
  });

  it('shares one request between concurrent callers', async () => {
    let requests = 0;
    const prompt = createNotificationPrompt({
      rowStore,
      request: async () => {
        requests += 1;
        return true;
      },
    });
    await Promise.all([prompt.ensureAsked(), prompt.ensureAsked(), prompt.ensureAsked()]);
    expect(requests).toBe(1);
  });

  it('does nothing when the platform has no permission request', async () => {
    const prompt = createNotificationPrompt({ rowStore, request: null });
    await prompt.ensureAsked();
    expect(prompt.visible.get()).toBe(false);
    expect(await rowStore.kvGet(STORAGE_KEYS.deviceNotificationPermissionAsked)).toBeNull();
  });

  it('hides the banner and asks again later when the request fails', async () => {
    let calls = 0;
    const prompt = createNotificationPrompt({
      rowStore,
      request: async () => {
        calls += 1;
        if (calls === 1) throw new Error('no activity');
        return true;
      },
    });
    await expect(prompt.ensureAsked()).rejects.toThrow('no activity');
    expect(prompt.visible.get()).toBe(false);
    await prompt.ensureAsked();
    expect(calls).toBe(2);
  });
});

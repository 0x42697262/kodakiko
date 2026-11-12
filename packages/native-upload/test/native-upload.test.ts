import { beforeEach, describe, expect, it, vi } from 'vitest';

const { nativeModule, listeners } = vi.hoisted(() => {
  const registered: Array<{ name: string; listener: (event: unknown) => void; removed: boolean }> = [];
  return {
    listeners: registered,
    nativeModule: {
      enqueue: vi.fn(async () => undefined),
      statuses: vi.fn(async () => ({ a: 'queued' })),
      cancelAll: vi.fn(async () => undefined),
      requestNotificationPermission: vi.fn(async () => true),
      addListener: vi.fn((name: string, listener: (event: unknown) => void) => {
        const entry = { name, listener, removed: false };
        registered.push(entry);
        return {
          remove: () => {
            entry.removed = true;
          },
        };
      }),
    },
  };
});

const requestedModules: string[] = [];

vi.mock('expo', () => ({
  requireNativeModule: (name: string) => {
    requestedModules.push(name);
    return nativeModule;
  },
}));

const api = await import('../src/index');

beforeEach(() => {
  listeners.length = 0;
  vi.clearAllMocks();
});

describe('native upload wrapper', () => {
  it('loads the native module by its registered name', () => {
    expect(requestedModules).toEqual(['KodakikoNativeUpload']);
  });

  it('forwards enqueue, statuses, cancelAll and the permission request', async () => {
    const job = { objectId: 'a', filePath: '/x', parts: [{ partNumber: 0, url: 'u', offset: 0, length: 1 }] };
    await api.enqueue([job]);
    expect(nativeModule.enqueue).toHaveBeenCalledWith([job]);
    expect(await api.statuses()).toEqual({ a: 'queued' });
    await api.cancelAll();
    expect(nativeModule.cancelAll).toHaveBeenCalledTimes(1);
    expect(await api.requestNotificationPermission()).toBe(true);
  });

  it('subscribes to the upload event and removes the subscription', () => {
    const seen: unknown[] = [];
    const subscription = api.addListener((event) => seen.push(event));
    expect(listeners[0]?.name).toBe('onUploadEvent');
    listeners[0]?.listener({ type: 'completed', objectId: 'a' });
    expect(seen).toEqual([{ type: 'completed', objectId: 'a' }]);
    subscription.remove();
    expect(listeners[0]?.removed).toBe(true);
  });
});

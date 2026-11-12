import { describe, expect, it, vi } from 'vitest';
import { createMemoryGlobalStore } from '../src/adapters/memory/memory-global-store';
import { NetworkError, type HttpClient } from '../src/adapters/http/http';
import { createConfigService } from '../src/services/config/config-service';

const CONFIG = {
  apiVersion: 1,
  signupMode: 'open',
  sharingEnabled: true,
  publicLinksEnabled: false,
  maxMediaPerEntry: 10,
  maxUploadBytes: 1000,
  defaultQuotaBytes: 0,
  trashDays: 14,
  accountDeletionGraceDays: 7,
  serverVersion: '0.1.0',
};

function lifecycleWith(listeners: Array<(active: boolean) => void>) {
  return {
    afterInteractions: async () => undefined,
    subscribeActive: (listener: (active: boolean) => void) => {
      listeners.push(listener);
      return () => undefined;
    },
    subscribeOnline: () => () => undefined,
  };
}

describe('config service', () => {
  it('loads the stored value at start, refreshes, notifies and keeps the cache on failure', async () => {
    const globalStore = createMemoryGlobalStore();
    globalStore.set('kodakiko.serverConfig', JSON.stringify({ ...CONFIG, trashDays: 30 }));
    let online = true;
    const request = vi.fn(async () => {
      if (!online) throw new NetworkError();
      return CONFIG;
    });
    const listeners: Array<(active: boolean) => void> = [];
    const service = createConfigService({
      http: { request } as unknown as HttpClient,
      globalStore,
      lifecycle: lifecycleWith(listeners),
    });
    expect(service.get()?.trashDays).toBe(30);

    const heard = vi.fn();
    service.subscribe(heard);
    await service.refresh();
    expect(service.get()?.trashDays).toBe(14);
    expect(heard).toHaveBeenCalledWith(CONFIG);
    expect(JSON.parse(globalStore.get('kodakiko.serverConfig') ?? 'null')).toEqual(CONFIG);

    online = false;
    expect(await service.refresh()).toEqual(CONFIG);
  });

  it('refreshes every time the app becomes active and ignores a corrupt stored value', async () => {
    const globalStore = createMemoryGlobalStore();
    globalStore.set('kodakiko.serverConfig', '{broken');
    const request = vi.fn(async () => CONFIG);
    const listeners: Array<(active: boolean) => void> = [];
    const service = createConfigService({
      http: { request } as unknown as HttpClient,
      globalStore,
      lifecycle: lifecycleWith(listeners),
    });
    expect(service.get()).toBeNull();
    const initialCalls = request.mock.calls.length;
    listeners[0]?.(true);
    listeners[0]?.(false);
    expect(request.mock.calls.length).toBe(initialCalls + 1);
  });

  it('accepts a config handed over by the server chooser: current, stored and announced', () => {
    const globalStore = createMemoryGlobalStore();
    const request = vi.fn(async () => {
      throw new NetworkError();
    });
    const service = createConfigService({
      http: { request } as unknown as HttpClient,
      globalStore,
      lifecycle: lifecycleWith([]),
    });
    const heard = vi.fn();
    service.subscribe(heard);

    service.accept(CONFIG as never);

    expect(service.get()).toEqual(CONFIG);
    expect(JSON.parse(globalStore.get('kodakiko.serverConfig') ?? 'null')).toEqual(CONFIG);
    expect(heard).toHaveBeenCalledWith(CONFIG);
  });
});

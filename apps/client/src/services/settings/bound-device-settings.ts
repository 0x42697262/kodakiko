import { EN } from '../../text/en';
import {
  createDeviceSettings,
  type DeviceSettings,
  type DeviceSettingsDeps,
} from './device-settings';

export type BoundDeviceSettings = {
  readonly settings: DeviceSettings;
  readonly load: () => Promise<void>;
  readonly unload: () => void;
};

export function createBoundDeviceSettings(deps: DeviceSettingsDeps): BoundDeviceSettings {
  let active: DeviceSettings | null = null;
  let detach: (() => void) | null = null;
  const listeners = new Set<() => void>();

  function notify(): void {
    for (const listener of [...listeners]) listener();
  }

  function unload(): void {
    detach?.();
    detach = null;
    active = null;
    notify();
  }

  return {
    settings: {
      get: () => active?.get() ?? deps.defaults,
      loaded: () => active?.loaded() ?? false,
      update: async (partial) => {
        if (active === null) throw new Error(EN['settings.notLoaded']);
        await active.update(partial);
      },
      subscribe: (listener) => {
        listeners.add(listener);
        return () => {
          listeners.delete(listener);
        };
      },
    },
    load: async () => {
      unload();
      const loaded = await createDeviceSettings(deps);
      active = loaded;
      detach = loaded.subscribe(notify);
      notify();
    },
    unload,
  };
}

import { useSyncExternalStore } from 'react';
import { useAppContext } from '../../composition/app-context';
import type { DeviceSettingsValue } from '../../services/settings/device-settings';

export function useDeviceSettings(): readonly [DeviceSettingsValue, (partial: Partial<DeviceSettingsValue>) => void] {
  const { services } = useAppContext();
  const value = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const update = (partial: Partial<DeviceSettingsValue>) => {
    services.deviceSettings.update(partial).catch(() => undefined);
  };
  return [value, update] as const;
}

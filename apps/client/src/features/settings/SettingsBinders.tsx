import * as ScreenCapture from 'expo-screen-capture';
import { useEffect } from 'react';
import { Platform } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { UI_SETTINGS_DEFAULTS } from '../../constants/ui-settings-constants';
import { setThemeChoice } from '../../ui/theme';

const SECURE_KEY = 'kodakiko.secureScreen';

export function SettingsBinders() {
  const { services } = useAppContext();
  useEffect(() => {
    let secure: boolean | null = null;
    const apply = () => {
      const loaded = services.deviceSettings.loaded();
      const settings = services.deviceSettings.get();
      if (loaded) setThemeChoice(settings.appTheme);
      const wanted = loaded ? settings.secureScreen : UI_SETTINGS_DEFAULTS.secureScreen;
      if (Platform.OS !== 'android' || secure === wanted) return;
      secure = wanted;
      const change = wanted
        ? ScreenCapture.preventScreenCaptureAsync(SECURE_KEY)
        : ScreenCapture.allowScreenCaptureAsync(SECURE_KEY);
      change.catch(() => undefined);
    };
    apply();
    return services.deviceSettings.subscribe(apply);
  }, [services.deviceSettings]);
  return null;
}

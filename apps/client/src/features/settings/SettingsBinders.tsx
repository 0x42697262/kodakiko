import * as NavigationBar from 'expo-navigation-bar';
import * as ScreenCapture from 'expo-screen-capture';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useSyncExternalStore } from 'react';
import { Platform } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { UI_SETTINGS_DEFAULTS } from '../../constants/ui-settings-constants';
import { setThemeChoice } from '../../ui/theme';
import { setFullScreenInsets } from '../../ui/system-insets';

const SECURE_KEY = 'kodakiko.secureScreen';

function applyNavigationBar(hidden: boolean): void {
  if (Platform.OS !== 'android') return;
  NavigationBar.setVisibilityAsync(hidden ? 'hidden' : 'visible').catch(() => undefined);
}

export function SettingsBinders() {
  const { services, lifecycle } = useAppContext();
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const fullScreen = Platform.OS === 'android' && settings.fullScreen;
  useEffect(() => {
    setFullScreenInsets(fullScreen);
  }, [fullScreen]);
  useEffect(() => {
    applyNavigationBar(fullScreen);
    return lifecycle.subscribeActive((active) => {
      if (active) applyNavigationBar(fullScreen);
    });
  }, [fullScreen, lifecycle]);
  useEffect(() => {
    let secure: boolean | null = null;
    const apply = () => {
      const loaded = services.deviceSettings.loaded();
      const settings = services.deviceSettings.get();
      if (loaded) setThemeChoice(settings.appTheme, settings.pureBlack);
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
  return <StatusBar hidden={fullScreen} style="auto" />;
}

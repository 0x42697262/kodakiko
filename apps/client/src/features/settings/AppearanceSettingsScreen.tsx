import { EN } from '../../text/en';
import { PreferenceList } from '../../ui/preferences/PreferenceList';
import { SettingsPage } from './settings-ui';
import { useDeviceSettings } from './use-device-settings';

export function AppearanceSettingsScreen() {
  const [settings, update] = useDeviceSettings();
  return (
    <SettingsPage testID="settings.appearanceScreen" title={EN['prefs.appearance']} padded={false}>
      <PreferenceList
        items={[
          {
            kind: 'choice',
            key: 'theme',
            testID: 'settings.theme',
            icon: 'appearance',
            title: EN['prefs.theme'],
            value: settings.appTheme,
            options: [
              { value: 'system', label: EN['prefs.themeSystem'] },
              { value: 'light', label: EN['prefs.themeLight'] },
              { value: 'dark', label: EN['prefs.themeDark'] },
            ],
            onChange: (value) => update({ appTheme: value as typeof settings.appTheme }),
          },
          {
            kind: 'choice',
            key: 'dateFormat',
            testID: 'settings.dateFormat',
            icon: 'calendar',
            title: EN['prefs.dateFormat'],
            value: settings.dateFormat,
            options: [
              { value: 'system', label: EN['prefs.dateSystem'] },
              { value: 'iso', label: EN['prefs.dateIso'] },
              { value: 'dmy', label: EN['prefs.dateDmy'] },
              { value: 'mdy', label: EN['prefs.dateMdy'] },
            ],
            onChange: (value) => update({ dateFormat: value as typeof settings.dateFormat }),
          },
          {
            kind: 'switch',
            key: 'relativeDates',
            testID: 'settings.relativeDates',
            title: EN['prefs.relativeDates'],
            summary: EN['prefs.relativeDatesSummary'],
            value: settings.relativeDates,
            onChange: (relativeDates) => update({ relativeDates }),
          },
          {
            kind: 'choice',
            key: 'startScreen',
            testID: 'settings.startScreen',
            icon: 'gallery',
            title: EN['prefs.startScreen'],
            value: settings.startScreen,
            options: [
              { value: 'gallery', label: EN['tabs.gallery'] },
              { value: 'calendar', label: EN['tabs.calendar'] },
            ],
            onChange: (value) => update({ startScreen: value as typeof settings.startScreen }),
          },
        ]}
      />
    </SettingsPage>
  );
}

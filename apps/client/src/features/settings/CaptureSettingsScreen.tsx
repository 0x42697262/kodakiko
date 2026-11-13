import { useState } from 'react';
import { useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { PreferenceList } from '../../ui/preferences/PreferenceList';
import { SettingsPage } from './settings-ui';
import { useDeviceSettings } from './use-device-settings';

const LOCATION_PROBE_MS = 10_000;

export function CaptureSettingsScreen() {
  const { services } = useAppContext();
  const [settings, update] = useDeviceSettings();
  const [locationDenied, setLocationDenied] = useState(false);
  const setLocation = (next: boolean) => {
    setLocationDenied(false);
    if (!next) {
      update({ locationForCaptures: false });
      return;
    }
    services.deviceSettings
      .update({ locationForCaptures: true })
      .then(() => services.location.currentPosition(LOCATION_PROBE_MS))
      .then((position) => {
        if (position !== null) return undefined;
        setLocationDenied(true);
        return services.deviceSettings.update({ locationForCaptures: false });
      })
      .catch(() => undefined);
  };
  return (
    <SettingsPage testID="settings.captureScreen" title={EN['prefs.capture']} padded={false}>
      {locationDenied ? <Banner testID="settings.locationDenied" kind="warning" message={EN['settings.locationDenied']} /> : null}
      <PreferenceList
        items={[
          { kind: 'switch', key: 'location', testID: 'settings.location', icon: 'info', title: EN['settings.location'], value: settings.locationForCaptures, onChange: setLocation },
          {
            kind: 'choice',
            key: 'startCamera',
            testID: 'settings.startCamera',
            icon: 'camera',
            title: EN['prefs.startCamera'],
            value: settings.captureStartCamera,
            options: [
              { value: 'back', label: EN['prefs.cameraBack'] },
              { value: 'front', label: EN['prefs.cameraFront'] },
            ],
            onChange: (value) => update({ captureStartCamera: value as typeof settings.captureStartCamera }),
          },
          { kind: 'switch', key: 'keepScreenOn', testID: 'settings.keepScreenOn', title: EN['prefs.keepScreenOn'], value: settings.captureKeepScreenOn, onChange: (captureKeepScreenOn) => update({ captureKeepScreenOn }) },
          { kind: 'info', key: 'noPhoneGallery', text: EN['prefs.noPhoneGallery'] },
        ]}
      />
    </SettingsPage>
  );
}

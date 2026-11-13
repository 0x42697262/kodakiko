import { useEffect, useState, useSyncExternalStore } from 'react';
import { Platform, Text } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { AUTO_LOCK_CHOICES } from '../../constants/settings-constants';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { PreferenceList } from '../../ui/preferences/PreferenceList';
import type { PreferenceItem } from '../../ui/preferences/preference-types';
import { Body } from '../../ui/Typography';
import { useTheme } from '../../ui/theme';
import { Sheet } from '../gallery/sheet';
import { SettingsPage } from './settings-ui';
import { useDeviceSettings } from './use-device-settings';

export function SecuritySettingsScreen() {
  const { services, keyVault, activeAccountKey } = useAppContext();
  const { colors } = useTheme();
  const [settings, update] = useDeviceSettings();
  const modeSwitch = useSyncExternalStore(services.thumbnails.modeSwitch.subscribe, services.thumbnails.modeSwitch.get);
  const [keepUnlocked, setKeepUnlocked] = useState(services.lock.keepUnlocked());
  const [biometric, setBiometric] = useState(false);
  const [confirmingThumbnails, setConfirmingThumbnails] = useState(false);
  const android = Platform.OS === 'android';

  useEffect(() => {
    const accountKey = activeAccountKey.get() ?? undefined;
    keyVault
      .capabilities(accountKey)
      .then((capabilities) => setBiometric(capabilities.biometric))
      .catch(() => setBiometric(false));
  }, [keyVault, activeAccountKey]);

  const items: PreferenceItem[] = [
    {
      kind: 'choice',
      key: 'autoLock',
      testID: 'settings.autoLock',
      icon: 'lock',
      title: EN['settings.autoLock'],
      summary: android ? EN['settings.autoLockBackground'](settings.autoLockMinutes) : EN['settings.autoLockIdle'](settings.autoLockMinutes),
      value: settings.autoLockMinutes,
      options: AUTO_LOCK_CHOICES.map((minutes) => ({ value: minutes, label: EN['prefs.autoLockMinutes'](minutes) })),
      onChange: (value) => update({ autoLockMinutes: Number(value) }),
    },
  ];
  if (!android) {
    items.push({
      kind: 'switch',
      key: 'keepUnlocked',
      testID: 'settings.keepUnlocked',
      title: EN['settings.keepUnlocked'],
      summary: EN['settings.keepUnlockedHint'],
      value: keepUnlocked,
      onChange: (next) => {
        services.lock
          .setKeepUnlocked(next)
          .then(() => setKeepUnlocked(services.lock.keepUnlocked()))
          .catch(() => undefined);
      },
    });
  }
  if (android) {
    items.push(
      { kind: 'text', key: 'biometric', testID: 'settings.biometric', icon: 'security', title: EN['settings.biometric'], summary: biometric ? EN['settings.biometricOn'] : EN['settings.biometricOff'] },
      { kind: 'switch', key: 'secureScreen', testID: 'settings.secureScreen', title: EN['prefs.secureScreen'], summary: EN['prefs.secureScreenSummary'], value: settings.secureScreen, onChange: (secureScreen) => update({ secureScreen }) },
      {
        kind: 'switch',
        key: 'encryptThumbnails',
        testID: 'settings.encryptThumbnails',
        title: EN['settings.encryptThumbnails'],
        summary: EN['settings.encryptThumbnailsHint'],
        value: settings.encryptThumbnails,
        disabled: modeSwitch.running,
        onChange: () => setConfirmingThumbnails(true),
      },
    );
  }
  items.push({ kind: 'switch', key: 'hideNotePreviews', testID: 'settings.hideNotePreviews', title: EN['prefs.hideNotePreviews'], value: settings.hideNotePreviews, onChange: (hideNotePreviews) => update({ hideNotePreviews }) });

  return (
    <SettingsPage testID="settings.securityScreen" title={EN['prefs.security']} padded={false}>
      <PreferenceList items={items} />
      {modeSwitch.running ? <Body testID="settings.thumbnailProgress" text={EN['settings.encryptThumbnailsProgress'](modeSwitch.done, modeSwitch.total)} /> : null}
      {confirmingThumbnails ? (
        <Sheet testID="settings.thumbnailSheet" onClose={() => setConfirmingThumbnails(false)}>
          <Text style={{ color: colors.text }}>{EN['settings.encryptThumbnailsConfirm']}</Text>
          <Button
            testID="settings.thumbnailConfirm"
            label={EN['settings.confirm']}
            onPress={() => {
              setConfirmingThumbnails(false);
              const encrypt = !settings.encryptThumbnails;
              services.deviceSettings
                .update({ encryptThumbnails: encrypt })
                .then(() => services.thumbnails.switchMode(encrypt))
                .catch(() => undefined);
            }}
          />
        </Sheet>
      ) : null}
    </SettingsPage>
  );
}

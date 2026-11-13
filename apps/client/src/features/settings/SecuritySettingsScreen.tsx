import { useEffect, useState, useSyncExternalStore } from 'react';
import { Platform, Text } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Body } from '../../ui/Typography';
import { useTheme } from '../../ui/theme';
import { Sheet } from '../gallery/sheet';
import { AUTO_LOCK_CHOICES } from '../../constants/settings-constants';
import { ChoiceRow, LabeledValue, SettingsPage, SwitchRow } from './settings-ui';

const LOCATION_PROBE_MS = 10_000;

export function SecuritySettingsScreen() {
  const { services, keyVault, activeAccountKey } = useAppContext();
  const { colors } = useTheme();
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const modeSwitch = useSyncExternalStore(services.thumbnails.modeSwitch.subscribe, services.thumbnails.modeSwitch.get);
  const [keepUnlocked, setKeepUnlocked] = useState(services.lock.keepUnlocked());
  const [biometric, setBiometric] = useState(false);
  const [confirmingThumbnails, setConfirmingThumbnails] = useState(false);
  const [locationDenied, setLocationDenied] = useState(false);
  const android = Platform.OS === 'android';

  useEffect(() => {
    const accountKey = activeAccountKey.get() ?? undefined;
    keyVault
      .capabilities(accountKey)
      .then((capabilities) => setBiometric(capabilities.biometric))
      .catch(() => setBiometric(false));
  }, [keyVault, activeAccountKey]);

  const lockLabel = (minutes: number) => String(minutes);

  return (
    <SettingsPage testID="settings.securityScreen" title={EN['settings.rowSecurity']}>
      <ChoiceRow
        testID="settings.autoLock"
        label={android ? EN['settings.autoLockBackground'](settings.autoLockMinutes) : EN['settings.autoLockIdle'](settings.autoLockMinutes)}
        choices={AUTO_LOCK_CHOICES}
        value={settings.autoLockMinutes}
        format={lockLabel}
        onChange={(autoLockMinutes) => services.deviceSettings.update({ autoLockMinutes }).catch(() => undefined)}
      />
      {android ? null : (
        <SwitchRow
          testID="settings.keepUnlocked"
          label={EN['settings.keepUnlocked']}
          hint={EN['settings.keepUnlockedHint']}
          value={keepUnlocked}
          onChange={(next) => {
            services.lock
              .setKeepUnlocked(next)
              .then(() => setKeepUnlocked(services.lock.keepUnlocked()))
              .catch(() => undefined);
          }}
        />
      )}
      {android ? (
        <LabeledValue testID="settings.biometric" label={EN['settings.biometric']} value={biometric ? EN['settings.biometricOn'] : EN['settings.biometricOff']} />
      ) : null}
      {android ? (
        <SwitchRow
          testID="settings.encryptThumbnails"
          label={EN['settings.encryptThumbnails']}
          hint={EN['settings.encryptThumbnailsHint']}
          value={settings.encryptThumbnails}
          disabled={modeSwitch.running}
          onChange={() => setConfirmingThumbnails(true)}
        />
      ) : null}
      {modeSwitch.running ? <Body testID="settings.thumbnailProgress" text={EN['settings.encryptThumbnailsProgress'](modeSwitch.done, modeSwitch.total)} /> : null}
      <SwitchRow
        testID="settings.location"
        label={EN['settings.location']}
        value={settings.locationForCaptures}
        onChange={(next) => {
          setLocationDenied(false);
          if (!next) {
            services.deviceSettings.update({ locationForCaptures: false }).catch(() => undefined);
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
        }}
      />
      {locationDenied ? <Banner testID="settings.locationDenied" kind="warning" message={EN['settings.locationDenied']} /> : null}
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

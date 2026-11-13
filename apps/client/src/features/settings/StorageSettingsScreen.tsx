import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { Platform, Text } from 'react-native';
import { z } from 'zod';
import { useAccountMode, useAppContext } from '../../composition/app-context';
import { STORAGE_KEYS } from '../../keys/storage-keys';
import type { CacheUsage } from '../../services/originals/originals-service';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { useTheme } from '../../ui/theme';
import { formatBytes } from '../format/format-bytes';
import { Sheet } from '../gallery/sheet';
import {
  LARGE_UPLOAD_THRESHOLD_CHOICES,
  ORIGINAL_CACHE_CAP_CHOICES,
} from '../../constants/settings-constants';
import { ChoiceRow, LabeledValue, SettingsPage, SwitchRow } from './settings-ui';

const NO_LIMIT = 0;

export function StorageSettingsScreen() {
  const { services, rowStore } = useAppContext();
  const { colors } = useTheme();
  const settings = useSyncExternalStore(
    services.deviceSettings.subscribe,
    services.deviceSettings.get,
  );
  const [usage, setUsage] = useState<CacheUsage | null>(null);
  const [persisted, setPersisted] = useState<boolean | null>(null);
  const [clearing, setClearing] = useState(false);
  const android = Platform.OS === 'android';
  const local = useAccountMode() === 'local';

  const reload = useCallback(() => {
    services.originals
      .usage()
      .then(setUsage)
      .catch(() => undefined);
    rowStore
      .kvGetJson(STORAGE_KEYS.deviceStoragePersisted, z.boolean())
      .then(setPersisted)
      .catch(() => setPersisted(null));
  }, [services.originals, rowStore]);

  useEffect(reload, [reload]);

  return (
    <SettingsPage testID="settings.storageScreen" title={EN['settings.rowStorage']}>
      {usage === null ? null : (
        <>
          <LabeledValue
            testID="settings.usageOriginals"
            label={EN['settings.usageOriginals']}
            value={formatBytes(usage.originalsBytes)}
          />
          <LabeledValue
            testID="settings.usageThumbnails"
            label={EN['settings.usageThumbnails']}
            value={formatBytes(usage.thumbnailsBytes)}
          />
          <LabeledValue
            testID="settings.usagePinned"
            label={EN['settings.usagePinned']}
            value={formatBytes(usage.pinnedBytes)}
          />
          <LabeledValue
            testID="settings.usagePending"
            label={EN['settings.usagePending']}
            value={formatBytes(usage.pendingUploadBytes)}
          />
        </>
      )}
      {local ? null : (
        <ChoiceRow
          testID="settings.cacheCap"
          label={EN['settings.cacheCap']}
          choices={ORIGINAL_CACHE_CAP_CHOICES}
          value={settings.originalCacheCapBytes}
          format={(value) => (value === NO_LIMIT ? EN['settings.noLimit'] : formatBytes(value))}
          onChange={(originalCacheCapBytes) => {
            services.deviceSettings
              .update({ originalCacheCapBytes })
              .then(() => services.originals.enforceCap())
              .then(reload)
              .catch(() => undefined);
          }}
        />
      )}
      {local ? null : (
        <Button
          testID="settings.clearOriginals"
          label={EN['settings.clearOriginals']}
          variant="secondary"
          onPress={() => setClearing(true)}
        />
      )}
      {local ? null : android ? (
        <>
          <SwitchRow
            testID="settings.wifiOnly"
            label={EN['settings.wifiOnly']}
            value={settings.largeUploadsOnWifiOnly}
            onChange={(largeUploadsOnWifiOnly) =>
              services.deviceSettings.update({ largeUploadsOnWifiOnly }).catch(() => undefined)
            }
          />
          <ChoiceRow
            testID="settings.largeThreshold"
            label={EN['settings.largeMeans']}
            choices={LARGE_UPLOAD_THRESHOLD_CHOICES}
            value={settings.largeUploadThresholdBytes}
            format={formatBytes}
            onChange={(largeUploadThresholdBytes) =>
              services.deviceSettings.update({ largeUploadThresholdBytes }).catch(() => undefined)
            }
          />
        </>
      ) : (
        <>
          <Text testID="settings.persistence" style={{ color: colors.text }}>
            {persisted === true ? EN['settings.persisted'] : EN['settings.notPersisted']}
          </Text>
          {persisted === true ? null : (
            <Button
              testID="settings.askPersist"
              label={EN['settings.askAgain']}
              variant="secondary"
              onPress={() => {
                navigator.storage
                  .persist()
                  .then((granted) =>
                    rowStore.kvSetJson(STORAGE_KEYS.deviceStoragePersisted, granted),
                  )
                  .then(reload)
                  .catch(() => undefined);
              }}
            />
          )}
        </>
      )}
      {clearing ? (
        <Sheet testID="settings.clearSheet" onClose={() => setClearing(false)}>
          <Text style={{ color: colors.text }}>{EN['settings.clearOriginalsConfirm']}</Text>
          <Button
            testID="settings.clearConfirm"
            label={EN['settings.clearAction']}
            variant="danger"
            onPress={() => {
              setClearing(false);
              services.originals
                .clearOriginals()
                .then(reload)
                .catch(() => undefined);
            }}
          />
        </Sheet>
      ) : null}
    </SettingsPage>
  );
}

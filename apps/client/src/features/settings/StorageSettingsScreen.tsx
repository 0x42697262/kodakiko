import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { useRouter, type Href } from 'expo-router';
import { Platform, StyleSheet, Text, View } from 'react-native';
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
import { PreferenceList } from '../../ui/preferences/PreferenceList';
import type { PreferenceItem } from '../../ui/preferences/preference-types';
import { SPACING } from '../../ui/theme-constants';
import { LabeledValue, SettingsPage } from './settings-ui';

const NO_LIMIT = 0;

export function StorageSettingsScreen() {
  const router = useRouter();
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

  const items: PreferenceItem[] = [
    { kind: 'text', key: 'export', testID: 'settings.row.export', icon: 'storage', title: EN['prefs.exportJournal'], summary: EN['prefs.exportSummary'], onPress: () => router.push('/settings/export' as Href) },
  ];
  if (!local) {
    items.push(
      {
        kind: 'choice',
        key: 'cacheCap',
        testID: 'settings.cacheCap',
        icon: 'pin',
        title: EN['settings.cacheCap'],
        value: settings.originalCacheCapBytes,
        options: ORIGINAL_CACHE_CAP_CHOICES.map((value) => ({ value, label: value === NO_LIMIT ? EN['settings.noLimit'] : formatBytes(value) })),
        onChange: (value) => {
          services.deviceSettings
            .update({ originalCacheCapBytes: Number(value) })
            .then(() => services.originals.enforceCap())
            .then(reload)
            .catch(() => undefined);
        },
      },
      { kind: 'text', key: 'clear', testID: 'settings.clearOriginals', icon: 'trash', title: EN['settings.clearOriginals'], onPress: () => setClearing(true) },
    );
    if (android) {
      items.push(
        { kind: 'switch', key: 'wifi', testID: 'settings.wifiOnly', icon: 'upload', title: EN['settings.wifiOnly'], value: settings.largeUploadsOnWifiOnly, onChange: (largeUploadsOnWifiOnly) => services.deviceSettings.update({ largeUploadsOnWifiOnly }).catch(() => undefined) },
        {
          kind: 'choice',
          key: 'threshold',
          testID: 'settings.largeThreshold',
          title: EN['settings.largeMeans'],
          value: settings.largeUploadThresholdBytes,
          options: LARGE_UPLOAD_THRESHOLD_CHOICES.map((value) => ({ value, label: formatBytes(value) })),
          onChange: (value) => services.deviceSettings.update({ largeUploadThresholdBytes: Number(value) }).catch(() => undefined),
        },
      );
    }
  }

  return (
    <SettingsPage testID="settings.storageScreen" title={EN['prefs.dataStorage']} padded={false}>
      <View style={styles.usage}>
        {usage === null ? null : (
          <>
            <LabeledValue testID="settings.usageOriginals" label={EN['settings.usageOriginals']} value={formatBytes(usage.originalsBytes)} />
            <LabeledValue testID="settings.usageThumbnails" label={EN['settings.usageThumbnails']} value={formatBytes(usage.thumbnailsBytes)} />
            <LabeledValue testID="settings.usagePinned" label={EN['settings.usagePinned']} value={formatBytes(usage.pinnedBytes)} />
            <LabeledValue testID="settings.usagePending" label={EN['settings.usagePending']} value={formatBytes(usage.pendingUploadBytes)} />
          </>
        )}
      </View>
      <PreferenceList items={items} />
      {android ? null : (
        <View style={styles.usage}>
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
                  .then((granted) => rowStore.kvSetJson(STORAGE_KEYS.deviceStoragePersisted, granted))
                  .then(reload)
                  .catch(() => undefined);
              }}
            />
          )}
        </View>
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

const styles = StyleSheet.create({ usage: { padding: SPACING.lg, gap: SPACING.sm } });

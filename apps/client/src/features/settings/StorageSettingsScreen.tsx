import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { type Href } from 'expo-router';
import { useRouter } from '../../navigation/use-router';
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
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { readDeviceStorage, type DeviceStorage } from '../../adapters/device-storage/device-storage';
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

  const [device, setDevice] = useState<DeviceStorage | null>(null);
  const reload = useCallback(() => {
    readDeviceStorage().then(setDevice).catch(() => setDevice(null));
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
    { kind: 'info', key: 'location', testID: 'storage.location', text: `${EN['storage.location']}: ${android ? EN['storage.locationAndroid'] : EN['storage.locationWeb']}` },
    { kind: 'header', key: 'backup', title: EN['storage.backup'] },
    { kind: 'text', key: 'export', testID: 'settings.row.export', icon: 'storage', title: EN['storage.createBackup'], summary: EN['prefs.exportSummary'], onPress: () => router.push('/settings/export' as Href) },
    { kind: 'text', key: 'import', testID: 'settings.row.import', icon: 'restore', title: EN['storage.restoreBackup'], summary: EN['storage.restoreSummary'], onPress: () => router.push('/settings/import' as Href) },
    { kind: 'header', key: 'cache', title: EN['storage.cache'] },
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
      { kind: 'text', key: 'clear', testID: 'settings.clearOriginals', icon: 'trash', title: EN['settings.clearOriginals'], ...(usage === null ? {} : { summary: EN['storage.used'](formatBytes(usage.originalsBytes)) }), onPress: () => setClearing(true) },
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
        <Text style={[styles.heading, { color: colors.primary }]}>{EN['storage.usage']}</Text>
        {device === null ? null : (
          <>
            <View style={[styles.track, { backgroundColor: colors.border }]}>
              <View style={[styles.fill, { width: `${((device.total - device.free) / Math.max(1, device.total)) * PERCENT}%`, backgroundColor: colors.primary }]} />
            </View>
            <Text testID="storage.device" style={{ color: colors.textMuted }}>
              {EN['storage.available'](formatBytes(device.free), formatBytes(device.total))}
            </Text>
          </>
        )}
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

const PERCENT = 100;
const BAR_HEIGHT = 8;

const styles = StyleSheet.create({
  usage: { padding: SPACING.lg, gap: SPACING.sm },
  heading: { fontSize: FONT_SIZE.caption, fontWeight: '600' },
  track: { height: BAR_HEIGHT, borderRadius: BAR_HEIGHT, overflow: 'hidden' },
  fill: { height: BAR_HEIGHT },
});

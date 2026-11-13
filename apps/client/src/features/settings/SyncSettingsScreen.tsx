import type { OutboxRow } from '../../adapters/rowstore/rowstore';
import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { Text } from 'react-native';
import { useAccountMode, useAppContext } from '../../composition/app-context';
import { useRouter, type Href } from 'expo-router';
import { listTestId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { Body } from '../../ui/Typography';
import { useTheme } from '../../ui/theme';
import { entryIdOfPayload } from '../../services/sync/outbox-rows';
import { SettingsPage } from './settings-ui';

const KIND_LABELS: Readonly<Record<string, string>> = Object.freeze({
  createEntry: EN['sync.kindCreateEntry'],
  updateEntryMeta: EN['sync.kindUpdateEntryMeta'],
  addMedia: EN['sync.kindAddMedia'],
  removeMedia: EN['sync.kindRemoveMedia'],
  trashEntry: EN['sync.kindTrashEntry'],
  restoreEntry: EN['sync.kindRestoreEntry'],
  deleteEntry: EN['sync.kindDeleteEntry'],
});

const ERROR_LABELS: Readonly<Record<string, string>> = Object.freeze({
  QUOTA_EXCEEDED: EN['sync.errorQuota'],
  FEATURE_DISABLED: EN['sync.errorDisabled'],
  VALIDATION_FAILED: EN['sync.errorRejected'],
  NETWORK: EN['sync.errorNetwork'],
});

const TIME_CHARS = 16;

export function deadItemText(row: OutboxRow): string {
  const kind = KIND_LABELS[row.kind] ?? row.kind;
  const error = row.lastErrorCode === null ? EN['sync.errorServer'] : ERROR_LABELS[row.lastErrorCode] ?? EN['sync.errorServer'];
  return `${kind} \u2014 ${entryIdOfPayload(row.payload) ?? ''} \u2014 ${error}`;
}

export function SyncSettingsScreen() {
  const { services, rowStore } = useAppContext();
  const { colors } = useTheme();
  const status = useSyncExternalStore(services.sync.subscribe, services.sync.status);
  const [dead, setDead] = useState<readonly OutboxRow[]>([]);
  const [busy, setBusy] = useState(false);
  const local = useAccountMode() === 'local';
  const router = useRouter();

  const reload = useCallback(() => {
    rowStore
      .transaction((tx) => tx.outbox.all())
      .then((rows) => setDead(rows.filter((row) => row.state === 'dead')))
      .catch(() => undefined);
  }, [rowStore]);

  useEffect(reload, [reload, status.dead, status.queued]);

  if (local) {
    return (
      <SettingsPage testID="settings.syncScreen" title={EN['settings.rowSync']}>
        <Body testID="settings.syncLocalOnly" text={EN['sync.localOnly']} />
        <Button testID="settings.syncConnect" label={EN['settings.connect']} onPress={() => router.push('/settings/connect' as Href)} />
      </SettingsPage>
    );
  }

  return (
    <SettingsPage testID="settings.syncScreen" title={EN['settings.rowSync']}>
      <Body
        testID="settings.lastSynced"
        text={status.lastSuccessAt === null ? EN['settings.notSynced'] : EN['settings.lastSynced'](new Date(status.lastSuccessAt).toISOString().slice(0, TIME_CHARS).replace('T', ' '))}
      />
      <Body testID="settings.waiting" text={EN['settings.waiting'](status.queued)} />
      <Body testID="settings.failedCount" text={EN['settings.failed'](status.dead)} />
      <Body testID="settings.waitingWifi" text={EN['settings.waitingWifi'](status.waitingForWifi)} />
      <Button
        testID="settings.syncNow"
        label={EN['settings.syncNow']}
        busy={busy}
        onPress={() => {
          setBusy(true);
          services.sync
            .syncNow()
            .catch(() => undefined)
            .finally(() => setBusy(false));
        }}
      />
      {status.dead > 0 ? (
        <Button testID="settings.retryFailed" label={EN['settings.retryFailed']} variant="secondary" onPress={() => services.sync.retryDead().then(reload).catch(() => undefined)} />
      ) : null}
      {dead.map((row, position) => (
        <Text key={row.id} testID={listTestId('settings.deadItem', position)} style={{ color: colors.text }}>
          {deadItemText(row)}
        </Text>
      ))}
    </SettingsPage>
  );
}

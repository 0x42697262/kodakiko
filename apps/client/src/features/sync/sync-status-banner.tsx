import { useSyncExternalStore } from 'react';
import { StyleSheet, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { SPACING } from '../../ui/theme-constants';
import { syncBannerModel } from './sync-banner-model';

export function SyncStatusBanner() {
  const { services } = useAppContext();
  const { sync, notificationPrompt } = services;
  const status = useSyncExternalStore(sync.subscribe, sync.status);
  const asking = useSyncExternalStore(notificationPrompt.visible.subscribe, notificationPrompt.visible.get);
  const model = syncBannerModel(status);
  return (
    <View style={styles.container}>
      {asking ? (
        <Banner
          testID={testId('sync.allowNotifications')}
          kind="info"
          message={EN['sync.allowNotifications']}
        />
      ) : null}
      {model === null ? null : (
        <View style={styles.row}>
          <Banner testID={`sync.${model.id}`} kind={model.kind} message={model.message} />
          {model.retry ? (
            <Button
              testID={testId('sync.retry')}
              label={EN['sync.retry']}
              variant="secondary"
              onPress={() => {
                sync.retryDead().catch(() => undefined);
              }}
            />
          ) : null}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: SPACING.sm },
  row: { gap: SPACING.sm },
});

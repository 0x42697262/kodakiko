import { useState, useSyncExternalStore, type ReactElement } from 'react';
import { RefreshControl, type RefreshControlProps } from 'react-native';
import { useAccountMode, useAppContext } from '../../composition/app-context';
import { haptics } from '../../ui/haptics';
import { useTheme } from '../../ui/theme';
import { pullToSyncEnabled, runPullToSync } from './pull-to-sync';

export function usePullToSync(): ReactElement<RefreshControlProps> | undefined {
  const { services } = useAppContext();
  const mode = useAccountMode();
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const { colors } = useTheme();
  const [refreshing, setRefreshing] = useState(false);
  if (!pullToSyncEnabled(mode, settings.syncPaused)) return undefined;
  return (
    <RefreshControl
      testID="pullToSync"
      refreshing={refreshing}
      colors={[colors.primary]}
      tintColor={colors.primary}
      progressBackgroundColor={colors.surface}
      onRefresh={() => {
        runPullToSync(services.sync.syncNow, setRefreshing, haptics.tick).catch(() => undefined);
      }}
    />
  );
}

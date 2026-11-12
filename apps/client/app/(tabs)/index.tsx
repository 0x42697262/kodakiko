import { StyleSheet, Text } from 'react-native';
import { useAccountState, useAppContext } from '../../src/composition/app-context';
import { SyncStatusBanner } from '../../src/features/sync/sync-status-banner';
import { EN } from '../../src/text/en';
import { Button } from '../../src/ui/Button';
import { Screen } from '../../src/ui/Screen';
import { FONT_SIZE } from '../../src/ui/theme-constants';
import { useTheme } from '../../src/ui/theme';

export default function GalleryPlaceholder() {
  const { services } = useAppContext();
  const state = useAccountState();
  const { colors } = useTheme();
  const username = state.kind === 'unlocked' ? state.username : '';
  return (
    <Screen testID="home.screen">
      <SyncStatusBanner />
      <Text testID="home.signedInAs" style={[styles.title, { color: colors.text }]}>
        {EN['home.signedInAs'](username)}
      </Text>
      <Button
        testID="home.signOut"
        label={EN['home.signOut']}
        onPress={() => {
          services.auth.signOut().catch(() => undefined);
        }}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({ title: { fontSize: FONT_SIZE.title, fontWeight: '600' } });

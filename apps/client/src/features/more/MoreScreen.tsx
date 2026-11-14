import { Image } from 'expo-image';
import LOGO from '../../../assets/icon-192.png';
import { type Href } from 'expo-router';
import { useRouter } from '../../navigation/use-router';
import { Linking, ScrollView, StyleSheet, View } from 'react-native';
import { Divider } from 'react-native-paper';
import { useAccountMode, useAppContext } from '../../composition/app-context';
import { APP_ROUTES } from '../../keys/routes';
import { EN } from '../../text/en';
import { PreferenceList } from '../../ui/preferences/PreferenceList';
import type { PreferenceItem } from '../../ui/preferences/preference-types';
import { useTheme } from '../../ui/theme';
import { SPACING } from '../../ui/theme-constants';
import { useSystemInsets } from '../../ui/system-insets';
import { useDeviceSettings } from '../settings/use-device-settings';

const LOGO_SIZE = 72;

export function MoreScreen() {
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const [settings, update] = useDeviceSettings();
  const local = useAccountMode() === 'local';
  const insets = useSystemInsets();
  const go = (path: string) => () => router.push(path as Href);
  const toggles: PreferenceItem[] = [
    { kind: 'switch', key: 'hideShared', testID: 'more.hideShared', icon: 'shared', title: EN['more.hideShared'], summary: EN['more.hideSharedSummary'], value: settings.hideShared, onChange: (hideShared) => update({ hideShared }) },
  ];
  if (!local) {
    toggles.push({
      kind: 'switch',
      key: 'pauseSync',
      testID: 'more.pauseSync',
      icon: 'sync',
      title: EN['more.pauseSync'],
      summary: EN['more.pauseSyncSummary'],
      value: settings.syncPaused,
      onChange: (syncPaused) => {
        update({ syncPaused });
        if (!syncPaused) services.sync.syncNow().catch(() => undefined);
      },
    });
  }
  const rows: PreferenceItem[] = [
    { kind: 'text', key: 'lockNow', testID: 'more.lockNow', icon: 'lock', title: EN['more.lockNow'], onPress: () => services.lock.lockNow().catch(() => undefined) },
    { kind: 'text', key: 'tags', testID: 'more.tags', icon: 'tags', title: EN['more.tags'], onPress: go('/tags') },
    { kind: 'text', key: 'statistics', testID: 'more.statistics', icon: 'statistics', title: EN['more.statistics'], onPress: go('/statistics') },
    { kind: 'text', key: 'trash', testID: 'more.trash', icon: 'trash', title: EN['more.trash'], onPress: go(APP_ROUTES.trash) },
    { kind: 'text', key: 'dataStorage', testID: 'more.dataStorage', icon: 'storage', title: EN['more.dataStorage'], onPress: go('/settings/storage') },
  ];
  const tail: PreferenceItem[] = [
    { kind: 'text', key: 'settings', testID: 'more.settings', icon: 'settings', title: EN['more.settings'], onPress: go('/settings') },
    { kind: 'text', key: 'about', testID: 'more.about', icon: 'info', title: EN['more.about'], onPress: go('/settings/about') },
    { kind: 'text', key: 'help', testID: 'more.help', icon: 'help', title: EN['more.help'], onPress: () => Linking.openURL(EN['more.helpUrl']).catch(() => undefined) },
  ];
  return (
    <ScrollView testID="more.screen" style={{ backgroundColor: colors.background }} contentContainerStyle={[styles.content, { paddingTop: insets.top }]}>
      <View style={styles.header}>
        <Image source={LOGO} style={styles.logo} accessibilityLabel={EN['more.appName']} />
      </View>
      <Divider />
      <PreferenceList items={toggles} />
      <Divider />
      <PreferenceList items={rows} />
      <Divider />
      <PreferenceList items={tail} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { paddingBottom: SPACING.xl },
  header: { alignItems: 'center', paddingVertical: SPACING.xl },
  logo: { width: LOGO_SIZE, height: LOGO_SIZE, borderRadius: SPACING.lg },
});

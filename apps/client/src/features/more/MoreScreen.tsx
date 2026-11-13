import { useRouter, type Href } from 'expo-router';
import { ScrollView, StyleSheet, View } from 'react-native';
import { Divider, List, Text } from 'react-native-paper';
import { useAppContext } from '../../composition/app-context';
import { APP_ROUTES } from '../../keys/routes';
import { EN } from '../../text/en';
import { Icon, type IconKey } from '../../ui/icons';
import { useTheme } from '../../ui/theme';
import { SPACING } from '../../ui/theme-constants';

const ROW_ICON_SIZE = 24;

type Row = { readonly key: string; readonly icon: IconKey; readonly title: string; readonly onPress: () => void };

export function MoreScreen() {
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const go = (path: string) => () => router.push(path as Href);
  const top: readonly Row[] = [
    { key: 'lockNow', icon: 'lock', title: EN['more.lockNow'], onPress: () => services.lock.lockNow().catch(() => undefined) },
  ];
  const rest: readonly Row[] = [
    { key: 'trash', icon: 'trash', title: EN['more.trash'], onPress: go(APP_ROUTES.trash) },
    { key: 'dataStorage', icon: 'storage', title: EN['more.dataStorage'], onPress: go('/settings/storage') },
    { key: 'settings', icon: 'settings', title: EN['more.settings'], onPress: go('/settings') },
    { key: 'about', icon: 'info', title: EN['more.about'], onPress: go('/settings/about') },
  ];
  const item = (row: Row) => (
    <List.Item
      key={row.key}
      testID={`more.${row.key}`}
      title={row.title}
      onPress={row.onPress}
      left={() => (
        <View style={styles.icon}>
          <Icon name={row.icon} size={ROW_ICON_SIZE} color={colors.primary} />
        </View>
      )}
    />
  );
  return (
    <ScrollView testID="more.screen" style={{ backgroundColor: colors.background }} contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Text variant="headlineMedium" style={{ color: colors.text }}>
          {EN['more.appName']}
        </Text>
      </View>
      <Divider />
      {top.map(item)}
      <Divider />
      {rest.map(item)}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { paddingBottom: SPACING.xl },
  header: { alignItems: 'center', paddingVertical: SPACING.xxl, gap: SPACING.sm },
  icon: { justifyContent: 'center', paddingLeft: SPACING.lg, minWidth: ROW_ICON_SIZE + SPACING.lg },
});

import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Icon, type IconKey } from './icons';
import { useTheme } from './theme';
import { FONT_SIZE, MIN_TOUCH_TARGET, SPACING } from './theme-constants';

export type RowAction = {
  readonly key: string;
  readonly icon: IconKey;
  readonly label: string;
  readonly testID: string;
  readonly onPress: () => void;
};

const ACTION_ICON_SIZE = 24;

export function ActionRow({ actions }: { readonly actions: readonly RowAction[] }) {
  const { colors } = useTheme();
  if (actions.length === 0) return null;
  return (
    <View style={styles.row}>
      {actions.map((action) => (
        <Pressable key={action.key} testID={action.testID} role="button" aria-label={action.label} onPress={action.onPress} style={styles.action}>
          <Icon name={action.icon} size={ACTION_ICON_SIZE} color={colors.primary} />
          <Text numberOfLines={1} style={[styles.label, { color: colors.textMuted }]}>
            {action.label}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-around', paddingVertical: SPACING.sm },
  action: { flex: 1, alignItems: 'center', gap: SPACING.xs, minHeight: MIN_TOUCH_TARGET },
  label: { fontSize: FONT_SIZE.caption },
});

import { StyleSheet, Text, View } from 'react-native';
import { BORDER_WIDTH, FONT_SIZE, RADIUS, SPACING } from './theme-constants';
import { useTheme } from './theme';

export type BannerKind = 'info' | 'warning' | 'error';

type BannerProps = {
  readonly testID: string;
  readonly kind: BannerKind;
  readonly message: string;
};

export function Banner({ testID, kind, message }: BannerProps) {
  const { colors } = useTheme();
  const accent = { info: colors.primary, warning: colors.textMuted, error: colors.danger }[kind];
  return (
    <View
      testID={testID}
      accessibilityRole={kind === 'error' ? 'alert' : 'text'}
      style={[styles.container, { backgroundColor: colors.surface, borderColor: accent }]}
    >
      <Text style={[styles.message, { color: colors.text }]}>{message}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: SPACING.md,
    borderRadius: RADIUS.sm,
    borderWidth: BORDER_WIDTH,
  },
  message: { fontSize: FONT_SIZE.body },
});

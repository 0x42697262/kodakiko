import { Pressable, StyleSheet, Text } from 'react-native';
import { Spinner } from './Spinner';
import {
  BORDER_WIDTH,
  DISABLED_OPACITY,
  FONT_SIZE,
  MIN_TOUCH_TARGET,
  RADIUS,
  SPACING,
} from './theme-constants';
import { useTheme } from './theme';

export type ButtonVariant = 'primary' | 'secondary' | 'danger';

type ButtonProps = {
  readonly testID: string;
  readonly label: string;
  readonly onPress: () => void;
  readonly variant?: ButtonVariant;
  readonly disabled?: boolean;
  readonly busy?: boolean;
};

export function Button({
  testID,
  label,
  onPress,
  variant = 'primary',
  disabled = false,
  busy = false,
}: ButtonProps) {
  const { colors } = useTheme();
  const inactive = disabled || busy;
  const background = { primary: colors.primary, secondary: colors.surface, danger: colors.danger }[
    variant
  ];
  const foreground = variant === 'secondary' ? colors.text : colors.onPrimary;
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: inactive, busy }}
      disabled={inactive}
      onPress={onPress}
      style={[
        styles.base,
        { backgroundColor: background, borderColor: colors.border },
        inactive && styles.inactive,
      ]}
    >
      {busy ? (
        <Spinner testID={`${testID}.busy`} color={foreground} />
      ) : (
        <Text style={[styles.label, { color: foreground }]}>{label}</Text>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    minHeight: MIN_TOUCH_TARGET,
    paddingHorizontal: SPACING.lg,
    borderRadius: RADIUS.sm,
    borderWidth: BORDER_WIDTH,
    alignItems: 'center',
    justifyContent: 'center',
  },
  inactive: { opacity: DISABLED_OPACITY },
  label: { fontSize: FONT_SIZE.headline, fontWeight: '600' },
});

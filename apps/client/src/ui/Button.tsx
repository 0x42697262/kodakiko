import { StyleSheet } from 'react-native';
import { Button as PaperButton } from 'react-native-paper';
import { FONT_SIZE, MIN_TOUCH_TARGET } from './theme-constants';
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

export function Button({ testID, label, onPress, variant = 'primary', disabled = false, busy = false }: ButtonProps) {
  const { colors } = useTheme();
  const inactive = disabled || busy;
  const colorProps =
    variant === 'danger'
      ? { buttonColor: colors.danger, textColor: colors.onPrimary }
      : variant === 'secondary'
        ? { textColor: colors.text }
        : {};
  return (
    <PaperButton
      testID={testID}
      accessibilityLabel={label}
      mode={variant === 'secondary' ? 'outlined' : 'contained'}
      disabled={inactive}
      loading={busy}
      onPress={onPress}
      contentStyle={styles.content}
      labelStyle={styles.label}
      {...colorProps}
    >
      {label}
    </PaperButton>
  );
}

const styles = StyleSheet.create({
  content: { minHeight: MIN_TOUCH_TARGET },
  label: { fontSize: FONT_SIZE.body, fontWeight: '600' },
});

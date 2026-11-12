import { StyleSheet, Text, TextInput, View } from 'react-native';
import { BORDER_WIDTH, FONT_SIZE, MIN_TOUCH_TARGET, RADIUS, SPACING } from './theme-constants';
import { useTheme } from './theme';

type TextFieldProps = {
  readonly testID: string;
  readonly label: string;
  readonly value: string;
  readonly onChangeText: (value: string) => void;
  readonly errorText?: string | null;
  readonly errorTestID?: string;
  readonly secureTextEntry?: boolean;
  readonly autoCapitalize?: 'none' | 'sentences' | 'words' | 'characters';
};

export function TextField({
  testID,
  label,
  value,
  onChangeText,
  errorText = null,
  errorTestID,
  secureTextEntry = false,
  autoCapitalize = 'none',
}: TextFieldProps) {
  const { colors } = useTheme();
  return (
    <View style={styles.container}>
      <Text style={[styles.label, { color: colors.textMuted }]}>{label}</Text>
      <TextInput
        testID={testID}
        accessibilityLabel={label}
        value={value}
        onChangeText={onChangeText}
        secureTextEntry={secureTextEntry}
        autoCapitalize={autoCapitalize}
        autoCorrect={false}
        style={[
          styles.input,
          {
            color: colors.text,
            backgroundColor: colors.surface,
            borderColor: errorText === null ? colors.border : colors.danger,
          },
        ]}
      />
      {errorText === null ? null : (
        <Text testID={errorTestID} style={[styles.error, { color: colors.danger }]}>
          {errorText}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: SPACING.xs },
  label: { fontSize: FONT_SIZE.caption },
  input: {
    minHeight: MIN_TOUCH_TARGET,
    paddingHorizontal: SPACING.md,
    borderRadius: RADIUS.sm,
    borderWidth: BORDER_WIDTH,
    fontSize: FONT_SIZE.body,
  },
  error: { fontSize: FONT_SIZE.caption },
});

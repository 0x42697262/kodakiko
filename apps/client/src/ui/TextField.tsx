import { Text, TextInput, View } from 'react-native';
import { useTheme } from './theme';
import { styles } from './text-field-styles';

type TextFieldProps = {
  readonly testID: string;
  readonly label: string;
  readonly value: string;
  readonly onChangeText: (value: string) => void;
  readonly errorText?: string | null;
  readonly errorTestID?: string;
  readonly secureTextEntry?: boolean;
  readonly autoCapitalize?: 'none' | 'sentences' | 'words' | 'characters';
  readonly onSubmit?: () => void;
  readonly multiline?: boolean;
  readonly showLabel?: boolean;
  readonly selectOnFocus?: boolean;
  readonly placeholder?: string;
  readonly maxLength?: number;
  readonly onKeyPress?: (key: string) => void;
  readonly editable?: boolean;
  readonly keyboardType?: 'default' | 'number-pad' | 'decimal-pad' | 'url';
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
  onSubmit,
  multiline = false,
  showLabel = true,
  selectOnFocus = false,
  placeholder,
  maxLength,
  onKeyPress,
  editable = true,
  keyboardType = 'default',
}: TextFieldProps) {
  const { colors } = useTheme();
  return (
    <View style={styles.container}>
      {showLabel ? <Text style={[styles.label, { color: colors.textMuted }]}>{label}</Text> : null}
      <TextInput
        testID={testID}
        accessibilityLabel={label}
        value={value}
        onChangeText={onChangeText}
        secureTextEntry={secureTextEntry}
        autoCapitalize={autoCapitalize}
        autoCorrect={false}
        selectTextOnFocus={selectOnFocus}
        multiline={multiline}
        onSubmitEditing={onSubmit}
        placeholder={placeholder}
        placeholderTextColor={colors.textMuted}
        maxLength={maxLength}
        editable={editable}
        keyboardType={keyboardType}
        onKeyPress={onKeyPress === undefined ? undefined : (event) => onKeyPress(event.nativeEvent.key)}
        submitBehavior={multiline ? 'newline' : 'submit'}
        textAlignVertical={multiline ? 'top' : 'center'}
        style={[
          styles.input,
          {
            color: colors.text,
            backgroundColor: colors.surface,
            borderColor: errorText === null ? colors.border : colors.danger,
          },
          multiline && styles.multiline,
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

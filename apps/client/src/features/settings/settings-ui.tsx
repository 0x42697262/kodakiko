import type { ComponentProps, ReactNode } from 'react';
import { Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Chip, List, Switch } from 'react-native-paper';
import { useSystemInsets } from '../../ui/system-insets';
import { SWITCH_DISABLED_OPACITY } from '../../constants/settings-constants';
import { AppBar } from '../../ui/AppBar';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { haptics } from '../../ui/haptics';

export function SettingsPage({
  testID,
  title,
  children,
  padded = true,
  actions,
}: {
  readonly testID: string;
  readonly title: string;
  readonly children: ReactNode;
  readonly padded?: boolean;
  readonly actions?: ComponentProps<typeof AppBar>['actions'];
}) {
  const { colors } = useTheme();
  const insets = useSystemInsets();
  return (
    <View
      testID={testID}
      style={[
        styles.page,
        {
          backgroundColor: colors.background,
          paddingTop: insets.top,
          paddingLeft: insets.left,
          paddingRight: insets.right,
        },
      ]}
    >
      <AppBar
        title={title}
        backTestID="settings.back"
        {...(actions === undefined ? {} : { actions })}
      />
      <ScrollView
        contentContainerStyle={padded ? styles.body : styles.flush}
        keyboardShouldPersistTaps="handled"
      >
        {children}
      </ScrollView>
    </View>
  );
}

export function SettingsRow({
  testID,
  label,
  detail,
  onPress,
}: {
  readonly testID: string;
  readonly label: string;
  readonly detail?: string;
  readonly onPress: () => void;
}) {
  const { colors } = useTheme();
  return (
    <List.Item
      testID={testID}
      accessibilityRole="button"
      title={label}
      description={detail}
      onPress={onPress}
      titleStyle={{ color: colors.text }}
      descriptionStyle={{ color: colors.textMuted }}
      style={styles.item}
      contentStyle={styles.itemContent}
    />
  );
}

export function SwitchRow({
  testID,
  label,
  value,
  disabled = false,
  hint,
  onChange,
}: {
  readonly testID: string;
  readonly label: string;
  readonly value: boolean;
  readonly disabled?: boolean;
  readonly hint?: string;
  readonly onChange: (next: boolean) => void;
}) {
  const { colors } = useTheme();
  return (
    <List.Item
      testID={testID}
      role="switch"
      aria-label={label}
      aria-checked={value}
      aria-disabled={disabled}
      disabled={disabled}
      title={label}
      description={hint}
      onPress={() => {
        haptics.tick('toggle');
        onChange(!value);
      }}
      titleStyle={{ color: colors.text }}
      descriptionStyle={{ color: colors.textMuted }}
      descriptionNumberOfLines={HINT_LINES}
      contentStyle={styles.itemContent}
      style={[styles.item, disabled ? { opacity: SWITCH_DISABLED_OPACITY } : null]}
      right={() => (
        <Switch
          value={value}
          disabled={disabled}
          onValueChange={(next) => {
            haptics.tick('toggle');
            onChange(next);
          }}
          pointerEvents={SWITCH_POINTER_EVENTS}
        />
      )}
    />
  );
}

export function ChoiceRow<Value extends string | number>({
  testID,
  label,
  choices,
  value,
  format,
  onChange,
}: {
  readonly testID: string;
  readonly label: string;
  readonly choices: readonly Value[];
  readonly value: Value;
  readonly format: (value: Value) => string;
  readonly onChange: (value: Value) => void;
}) {
  const { colors } = useTheme();
  return (
    <View style={styles.group}>
      <Text style={[styles.label, { color: colors.text }]}>{label}</Text>
      <View style={styles.choices}>
        {choices.map((choice) => (
          <Chip
            key={String(choice)}
            testID={`${testID}.${String(choice)}`}
            accessibilityRole="button"
            selected={choice === value}
            showSelectedCheck
            mode="outlined"
            onPress={() => onChange(choice)}
          >
            {format(choice)}
          </Chip>
        ))}
      </View>
    </View>
  );
}

export function LabeledValue({
  testID,
  label,
  value,
}: {
  readonly testID?: string;
  readonly label: string;
  readonly value: string;
}) {
  const { colors } = useTheme();
  return (
    <View style={styles.valueRow}>
      <Text style={{ color: colors.textMuted }}>{label}</Text>
      <Text testID={testID} style={[styles.label, { color: colors.text }]}>
        {value}
      </Text>
    </View>
  );
}

const HINT_LINES = 4;
const SWITCH_POINTER_EVENTS = Platform.OS === 'web' ? 'none' : 'auto';

const styles = StyleSheet.create({
  page: { flex: 1 },
  body: { gap: SPACING.md, padding: SPACING.lg },
  flush: { paddingBottom: SPACING.xl },
  group: { gap: SPACING.xs },
  item: { paddingLeft: 0, paddingRight: 0 },
  itemContent: { paddingLeft: 0 },
  label: { fontSize: FONT_SIZE.body },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  valueRow: { flexDirection: 'row', justifyContent: 'space-between', gap: SPACING.md },
});

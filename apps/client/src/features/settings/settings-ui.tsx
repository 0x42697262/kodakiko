import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { SWITCH_DISABLED_OPACITY, SWITCH_TRACK_PADDING } from '../../constants/settings-constants';
import { Button } from '../../ui/Button';
import { AppBar } from '../../ui/AppBar';
import { Screen } from '../../ui/Screen';
import { BORDER_WIDTH, FONT_SIZE, MIN_TOUCH_TARGET, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';

export function SettingsPage({ testID, title, children }: { readonly testID: string; readonly title: string; readonly children: ReactNode }) {
  return (
    <View style={styles.page}>
      <AppBar title={title} backTestID="settings.back" />
      <Screen testID={testID} scroll>
        <View style={styles.body}>{children}</View>
      </Screen>
    </View>
  );
}

export function SettingsRow({ testID, label, detail, onPress }: { readonly testID: string; readonly label: string; readonly detail?: string; readonly onPress: () => void }) {
  const { colors } = useTheme();
  return (
    <Pressable testID={testID} accessibilityRole="button" onPress={onPress} style={[styles.row, { borderColor: colors.border }]}>
      <Text style={[styles.label, { color: colors.text }]}>{label}</Text>
      {detail === undefined ? null : <Text style={{ color: colors.textMuted }}>{detail}</Text>}
    </Pressable>
  );
}

export function SwitchRow({ testID, label, value, disabled = false, hint, onChange }: {
  readonly testID: string;
  readonly label: string;
  readonly value: boolean;
  readonly disabled?: boolean;
  readonly hint?: string;
  readonly onChange: (next: boolean) => void;
}) {
  const { colors } = useTheme();
  return (
    <View style={styles.group}>
      <Pressable
        testID={testID}
        role="switch"
        aria-label={label}
        aria-checked={value}
        aria-disabled={disabled}
        disabled={disabled}
        onPress={() => onChange(!value)}
        style={[styles.row, styles.switchRow, { borderColor: colors.border, opacity: disabled ? SWITCH_DISABLED_OPACITY : 1 }]}
      >
        <Text style={[styles.label, { color: colors.text }]}>{label}</Text>
        <View style={[styles.track, { backgroundColor: value ? colors.primary : colors.border }]}>
          <View style={[styles.knob, { backgroundColor: colors.background, alignSelf: value ? 'flex-end' : 'flex-start' }]} />
        </View>
      </Pressable>
      {hint === undefined ? null : <Text style={{ color: colors.textMuted }}>{hint}</Text>}
    </View>
  );
}

export function ChoiceRow<Value extends string | number>({ testID, label, choices, value, format, onChange }: {
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
          <Button
            key={String(choice)}
            testID={`${testID}.${String(choice)}`}
            label={format(choice)}
            variant={choice === value ? 'primary' : 'secondary'}
            onPress={() => onChange(choice)}
          />
        ))}
      </View>
    </View>
  );
}

export function LabeledValue({ testID, label, value }: { readonly testID?: string; readonly label: string; readonly value: string }) {
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

const KNOB = 20;
const TRACK_WIDTH = 44;

const styles = StyleSheet.create({
  page: { flex: 1 },
  body: { gap: SPACING.md },
  group: { gap: SPACING.xs },
  row: { minHeight: MIN_TOUCH_TARGET, padding: SPACING.md, borderWidth: BORDER_WIDTH, borderRadius: RADIUS.md, gap: SPACING.xs, justifyContent: 'center' },
  switchRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  label: { fontSize: FONT_SIZE.body },
  track: { width: TRACK_WIDTH, padding: SWITCH_TRACK_PADDING, borderRadius: KNOB },
  knob: { width: KNOB, height: KNOB, borderRadius: KNOB / SWITCH_TRACK_PADDING },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  valueRow: { flexDirection: 'row', justifyContent: 'space-between', gap: SPACING.md },
});

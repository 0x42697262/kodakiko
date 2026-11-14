import { useState, type ReactNode } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { Dialog, Portal, RadioButton, Switch } from 'react-native-paper';
import { Icon, type IconKey } from '../icons';
import { useTheme } from '../theme';
import { FONT_SIZE, MIN_TOUCH_TARGET, SPACING } from '../theme-constants';
import { choiceSummary, type PreferenceItem } from './preference-types';

const ICON_SIZE = 24;
const ICON_COLUMN = 56;
const DISABLED_OPACITY = 0.5;
const SWITCH_POINTER_EVENTS = Platform.OS === 'web' ? 'none' : 'auto';

function Row({
  testID,
  title,
  summary,
  icon,
  danger = false,
  disabled = false,
  right,
  onPress,
  role,
  checked,
}: {
  readonly testID: string;
  readonly title: string;
  readonly summary?: string | undefined;
  readonly icon?: IconKey | undefined;
  readonly danger?: boolean;
  readonly disabled?: boolean;
  readonly right?: ReactNode;
  readonly onPress?: (() => void) | undefined;
  readonly role?: 'switch' | 'button';
  readonly checked?: boolean;
}) {
  const { colors } = useTheme();
  return (
    <Pressable
      testID={testID}
      role={role ?? 'button'}
      aria-label={title}
      {...(checked === undefined ? {} : { 'aria-checked': checked })}
      aria-disabled={disabled}
      disabled={disabled || onPress === undefined}
      onPress={onPress}
      style={({ pressed }) => [styles.row, { opacity: disabled ? DISABLED_OPACITY : 1, backgroundColor: pressed ? colors.surface : 'transparent' }]}
    >
      <View style={styles.iconColumn}>{icon === undefined ? null : <Icon name={icon} size={ICON_SIZE} color={danger ? colors.danger : colors.primary} />}</View>
      <View style={styles.texts}>
        <Text style={[styles.title, { color: danger ? colors.danger : colors.text }]}>{title}</Text>
        {summary === undefined || summary === '' ? null : <Text style={[styles.summary, { color: colors.textMuted }]}>{summary}</Text>}
      </View>
      {right === undefined ? null : <View style={styles.right}>{right}</View>}
    </Pressable>
  );
}

function ChoiceDialog({ item, onClose }: { readonly item: Extract<PreferenceItem, { kind: 'choice' }>; readonly onClose: () => void }) {
  return (
    <Portal>
      <Dialog visible onDismiss={onClose} testID={`${item.testID}.dialog`}>
        <Dialog.Title>{item.title}</Dialog.Title>
        <Dialog.Content>
          <RadioButton.Group
            value={String(item.value)}
            onValueChange={(next) => {
              const chosen = item.options.find((option) => String(option.value) === next);
              if (chosen !== undefined) item.onChange(chosen.value);
              onClose();
            }}
          >
            {item.options.map((option) => (
              <RadioButton.Item
                key={String(option.value)}
                testID={`${item.testID}.${String(option.value)}`}
                label={option.label}
                value={String(option.value)}
              />
            ))}
          </RadioButton.Group>
        </Dialog.Content>
      </Dialog>
    </Portal>
  );
}

export function PreferenceList({ items }: { readonly items: readonly PreferenceItem[] }) {
  const { colors } = useTheme();
  const [openChoice, setOpenChoice] = useState<string | null>(null);
  return (
    <View>
      {items.map((item) => {
        switch (item.kind) {
          case 'header':
            return (
              <Text key={item.key} style={[styles.header, { color: colors.primary }]}>
                {item.title}
              </Text>
            );
          case 'info':
            return (
              <View key={item.key} style={styles.info}>
                <Icon name="info" size={ICON_SIZE} color={colors.textMuted} />
                <Text testID={item.testID} style={[styles.summary, styles.infoText, { color: colors.textMuted }]}>
                  {item.text}
                </Text>
              </View>
            );
          case 'text':
            return <Row key={item.key} testID={item.testID} title={item.title} summary={item.summary} icon={item.icon} danger={item.danger ?? false} onPress={item.onPress} />;
          case 'switch':
            return (
              <Row
                key={item.key}
                testID={item.testID}
                title={item.title}
                summary={item.summary}
                icon={item.icon}
                role="switch"
                checked={item.value}
                disabled={item.disabled ?? false}
                onPress={() => item.onChange(!item.value)}
                right={
                  <Switch
                    testID={`${item.testID}.switch`}
                    value={item.value}
                    disabled={item.disabled ?? false}
                    onValueChange={item.onChange}
                    pointerEvents={SWITCH_POINTER_EVENTS}
                  />
                }
              />
            );
          case 'choice':
            return (
              <View key={item.key}>
                <Row testID={item.testID} title={item.title} summary={item.summary ?? choiceSummary(item)} icon={item.icon} onPress={() => setOpenChoice(item.key)} />
                {openChoice === item.key ? <ChoiceDialog item={item} onClose={() => setOpenChoice(null)} /> : null}
              </View>
            );
          default:
            return null;
        }
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', minHeight: MIN_TOUCH_TARGET + SPACING.lg, paddingVertical: SPACING.md, paddingRight: SPACING.lg },
  iconColumn: { width: ICON_COLUMN, alignItems: 'center' },
  texts: { flex: 1, gap: SPACING.xs },
  title: { fontSize: FONT_SIZE.body },
  summary: { fontSize: FONT_SIZE.caption },
  right: { paddingLeft: SPACING.md },
  header: { fontSize: FONT_SIZE.caption, fontWeight: '600', paddingLeft: ICON_COLUMN, paddingTop: SPACING.lg, paddingBottom: SPACING.xs },
  info: { flexDirection: 'row', gap: SPACING.lg, paddingHorizontal: SPACING.lg, paddingVertical: SPACING.md },
  infoText: { flex: 1 },
});

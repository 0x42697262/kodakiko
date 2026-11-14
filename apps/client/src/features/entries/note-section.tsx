import { NOTE_MAX_CHARS } from '@kodakiko/contract';
import { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { IconAction } from '../../ui/IconAction';
import type { IconKey } from '../../ui/icons';
import { TextField } from '../../ui/TextField';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { Touchable } from '../../ui/Touchable';
import { MarkdownView } from '../markdown/markdown-view';
import { applyMarkdownAction, type MarkdownAction, type Selection } from '../markdown/toolbar';
import { formatClock } from '../format/format-clock';
import { useDeviceSettings } from '../settings/use-device-settings';

const TOOLBAR: readonly {
  readonly action: MarkdownAction;
  readonly icon: IconKey;
  readonly label: string;
}[] = [
  { action: 'bold', icon: 'bold', label: EN['toolbar.bold'] },
  { action: 'italic', icon: 'italic', label: EN['toolbar.italic'] },
  { action: 'strike', icon: 'strike', label: EN['toolbar.strike'] },
  { action: 'heading', icon: 'heading', label: EN['toolbar.heading'] },
  { action: 'bullet', icon: 'bullet', label: EN['toolbar.bullet'] },
  { action: 'checklist', icon: 'checklist', label: EN['toolbar.checklist'] },
  { action: 'quote', icon: 'quote', label: EN['toolbar.quote'] },
  { action: 'link', icon: 'link', label: EN['toolbar.link'] },
  { action: 'time', icon: 'time', label: EN['toolbar.time'] },
];

const START: Selection = { start: 0, end: 0 };
const CLOCK_PAD = 2;

function clockText(now: number): string {
  const date = new Date(now);
  return `${String(date.getHours()).padStart(CLOCK_PAD, '0')}:${String(date.getMinutes()).padStart(CLOCK_PAD, '0')}`;
}

export function NoteSection({
  note,
  onChange,
}: {
  readonly note: string;
  readonly onChange: (note: string) => void;
}) {
  const { colors } = useTheme();
  const { clock } = useAppContext();
  const [settings] = useDeviceSettings();
  const [editing, setEditing] = useState(false);
  const [selection, setSelection] = useState<Selection>(START);
  const [forced, setForced] = useState<Selection | undefined>(undefined);

  if (!editing) {
    if (note.trim() === '') {
      return (
        <Touchable
          testID={testId('edit.addNote')}
          accessibilityRole="button"
          onPress={() => setEditing(true)}
          style={[styles.addRow, { borderColor: colors.border }]}
        >
          <Text style={{ color: colors.textMuted }}>{EN['edit.addNote']}</Text>
        </Touchable>
      );
    }
    return (
      <View style={styles.rendered}>
        <View style={styles.renderedText}>
          <MarkdownView source={note} />
        </View>
        <IconAction
          testID={testId('edit.editNote')}
          icon="edit"
          label={EN['edit.editNote']}
          tonal={false}
          onPress={() => setEditing(true)}
        />
      </View>
    );
  }

  function run(action: MarkdownAction): void {
    const result = applyMarkdownAction(
      action,
      note,
      selection,
      formatClock(clockText(clock.now()), settings.timeFormat),
    );
    onChange(result.text);
    setSelection(result.selection);
    setForced(result.selection);
  }

  return (
    <View style={styles.editor}>
      <ScrollView
        horizontal
        keyboardShouldPersistTaps="always"
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.toolbar}
      >
        {TOOLBAR.map((item) => (
          <IconAction
            key={item.action}
            testID={`toolbar.${item.action}`}
            icon={item.icon}
            label={item.label}
            tonal={false}
            onPress={() => run(item.action)}
          />
        ))}
        <IconAction
          testID={testId('edit.noteDone')}
          icon="save"
          label={EN['edit.noteDone']}
          tonal={false}
          onPress={() => setEditing(false)}
        />
      </ScrollView>
      <TextField
        testID={testId('compose.note')}
        label={EN['compose.note']}
        showLabel={false}
        placeholder={EN['compose.notePlaceholder']}
        value={note}
        onChangeText={(text) => {
          setForced(undefined);
          onChange(text);
        }}
        selection={forced}
        onSelectionChange={(next) => {
          setForced(undefined);
          setSelection(next);
        }}
        multiline
        autoFocus
        autoCapitalize="sentences"
        maxLength={NOTE_MAX_CHARS}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  addRow: { borderWidth: StyleSheet.hairlineWidth, borderRadius: SPACING.sm, padding: SPACING.lg },
  rendered: { flexDirection: 'row', alignItems: 'flex-start', gap: SPACING.sm },
  renderedText: { flex: 1 },
  editor: { gap: SPACING.xs },
  toolbar: { gap: SPACING.xs, alignItems: 'center' },
  label: { fontSize: FONT_SIZE.caption },
});

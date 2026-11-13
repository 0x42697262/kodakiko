import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { TAG_MAX_CHARS } from '@kodakiko/contract';
import { addTag, removeTag, type TagRejection } from '@kodakiko/core';
import { BACKSPACE_KEY, TAG_SEPARATOR_KEY, TAG_SUGGESTIONS_MAX } from '../../constants/entry-constants';
import { ENTRY_IDS } from '../../keys/entry-ids';
import { listTestId, testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Hint } from '../../ui/Typography';
import { BORDER_WIDTH, FONT_SIZE, MIN_TOUCH_TARGET, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';

export type TagInputProps = {
  readonly tags: readonly string[];
  readonly onChange: (tags: readonly string[]) => void;
  readonly suggestions: (prefix: string) => readonly string[];
  readonly maxTags: number;
  readonly disabled?: boolean;
};

function hintText(rejected: TagRejection, maxTags: number): string {
  switch (rejected) {
    case 'empty':
      return EN['tags.empty'];
    case 'tooLong':
      return EN['tags.tooLong'](TAG_MAX_CHARS);
    case 'tooMany':
      return EN['tags.tooMany'](maxTags);
    case 'duplicate':
      return EN['tags.duplicate'];
  }
}

const HINT_KEYS = {
  empty: 'tags.empty',
  tooLong: 'tags.tooLong',
  tooMany: 'tags.tooMany',
  duplicate: 'tags.duplicate',
} as const satisfies Record<TagRejection, Parameters<typeof testId>[0]>;

export function TagInput({ tags, onChange, suggestions, maxTags, disabled = false }: TagInputProps) {
  const { colors } = useTheme();
  const [text, setText] = useState('');
  const [rejected, setRejected] = useState<TagRejection | null>(null);

  function commit(raw: readonly string[]): boolean {
    let next: readonly string[] = tags;
    for (const part of raw) {
      const result = addTag(next, part);
      if (result.rejected !== null) {
        setRejected(result.rejected);
        if (next !== tags) onChange(next);
        return false;
      }
      next = result.tags;
    }
    setRejected(null);
    onChange(next);
    return true;
  }

  function changeText(value: string): void {
    if (!value.includes(TAG_SEPARATOR_KEY)) {
      setText(value);
      setRejected(null);
      return;
    }
    const parts = value.split(TAG_SEPARATOR_KEY);
    const remainder = parts.pop() ?? '';
    const complete = parts.filter((part) => part.trim() !== '');
    if (complete.length > 0) commit(complete);
    setText(remainder);
  }

  function submit(): void {
    if (commit([text])) setText('');
  }

  function pressKey(key: string): void {
    if (key === BACKSPACE_KEY && text === '' && tags.length > 0) {
      const last = tags[tags.length - 1];
      if (last !== undefined) onChange(removeTag(tags, last));
    }
  }

  function pickSuggestion(name: string): void {
    if (commit([name])) setText('');
  }

  const shown = suggestions(text)
    .filter((name) => !tags.includes(name))
    .slice(0, TAG_SUGGESTIONS_MAX);

  return (
    <View style={styles.container}>
      <View style={styles.chips}>
        {tags.map((name, position) => (
          <View
            key={name}
            style={[styles.chip, { backgroundColor: colors.surface, borderColor: colors.border }]}
          >
            <Text
              testID={listTestId(ENTRY_IDS.tagChip, position)}
              style={[styles.chipText, { color: colors.text }]}
            >
              {name}
            </Text>
            <Pressable
              testID={listTestId(ENTRY_IDS.tagRemove, position)}
              accessibilityRole="button"
              accessibilityLabel={EN['tags.removeChip'](name)}
              disabled={disabled}
              onPress={() => onChange(removeTag(tags, name))}
              style={styles.remove}
            >
              <Text style={{ color: colors.textMuted }}>{EN['tags.removeGlyph']}</Text>
            </Pressable>
          </View>
        ))}
      </View>
      <View style={styles.row}>
        <View style={styles.field}>
          <TextField
            testID={testId('tags.field')}
            label={EN['tags.field']}
            showLabel={false}
            placeholder={EN['tags.field']}
            value={text}
            onChangeText={changeText}
            onSubmit={submit}
            onKeyPress={pressKey}
            editable={!disabled}
          />
        </View>
        <Button
          testID={testId('tags.add')}
          label={EN['tags.add']}
          variant="secondary"
          disabled={disabled}
          onPress={submit}
        />
      </View>
      {rejected === null ? null : (
        <Hint testID={testId(HINT_KEYS[rejected])} text={hintText(rejected, maxTags)} />
      )}
      {shown.length === 0 ? null : (
        <View style={styles.chips}>
          {shown.map((name, position) => (
            <Pressable
              key={name}
              testID={listTestId(ENTRY_IDS.tagSuggestion, position)}
              accessibilityRole="button"
              accessibilityLabel={name}
              disabled={disabled}
              onPress={() => pickSuggestion(name)}
              style={[styles.chip, { borderColor: colors.border }]}
            >
              <Text style={[styles.chipText, { color: colors.textMuted }]}>{name}</Text>
            </Pressable>
          ))}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: SPACING.sm },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: MIN_TOUCH_TARGET,
    paddingHorizontal: SPACING.md,
    borderRadius: RADIUS.md,
    borderWidth: BORDER_WIDTH,
  },
  chipText: { fontSize: FONT_SIZE.body },
  remove: { paddingLeft: SPACING.sm, minHeight: MIN_TOUCH_TARGET, justifyContent: 'center' },
  row: { flexDirection: 'row', gap: SPACING.sm, alignItems: 'flex-end' },
  field: { flex: 1 },
});

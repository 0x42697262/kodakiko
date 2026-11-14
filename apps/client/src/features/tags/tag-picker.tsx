import { TAGS_PER_ENTRY_MAX } from '@kodakiko/contract';
import { type Href } from 'expo-router';
import { useRouter } from '../../navigation/use-router';
import { useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Button as PaperButton, Checkbox, Dialog, Portal } from 'react-native-paper';
import { useAppContext } from '../../composition/app-context';
import { ENTRY_IDS } from '../../keys/entry-ids';
import { listTestId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Icon } from '../../ui/icons';
import { useTheme } from '../../ui/theme';
import { FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useIndexVersion } from '../gallery/hooks';
import { useDeviceSettings } from '../settings/use-device-settings';
import { orderTags } from './tag-order';

const REMOVE_ICON = 16;
export const TAGS_ROUTE = '/tags';

export function useTagChoices(): readonly string[] {
  const { services } = useAppContext();
  const version = useIndexVersion();
  const [settings] = useDeviceSettings();
  return useMemo(() => orderTags(services.index.index()?.allTags() ?? [], settings.tagOrder).map((item) => item.tag), [services.index, version, settings.tagOrder]);
}

export function TagPickerDialog({
  visible,
  selected,
  onChange,
  onClose,
  multiple = true,
}: {
  readonly visible: boolean;
  readonly selected: readonly string[];
  readonly onChange: (tags: readonly string[]) => void;
  readonly onClose: () => void;
  readonly multiple?: boolean;
}) {
  const router = useRouter();
  const { colors } = useTheme();
  const choices = useTagChoices();
  const toggle = (tag: string) => {
    if (!multiple) {
      onChange(selected.includes(tag) ? [] : [tag]);
      return;
    }
    onChange(selected.includes(tag) ? selected.filter((item) => item !== tag) : [...selected, tag]);
  };
  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onClose} testID="tags.picker">
        <Dialog.Title>{EN['tags.pickerTitle']}</Dialog.Title>
        <Dialog.ScrollArea>
          {choices.length === 0 ? <Text style={[styles.empty, { color: colors.textMuted }]}>{EN['tags.noneToPick']}</Text> : null}
          {choices.map((tag) => (
            <Checkbox.Item
              key={tag}
              testID={`tags.option.${tag}`}
              label={tag}
              status={selected.includes(tag) ? 'checked' : 'unchecked'}
              disabled={multiple && !selected.includes(tag) && selected.length >= TAGS_PER_ENTRY_MAX}
              position="leading"
              labelStyle={styles.label}
              onPress={() => toggle(tag)}
            />
          ))}
        </Dialog.ScrollArea>
        <Dialog.Actions>
          <PaperButton
            testID="tags.editTags"
            onPress={() => {
              onClose();
              router.push(TAGS_ROUTE as Href);
            }}
          >
            {EN['tags.editTags']}
          </PaperButton>
          <PaperButton testID="tags.done" onPress={onClose}>
            {EN['tags.done']}
          </PaperButton>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}

export function TagPicker({ tags, onChange, readOnly = false }: { readonly tags: readonly string[]; readonly onChange: (tags: readonly string[]) => void; readonly readOnly?: boolean }) {
  const { colors } = useTheme();
  const [open, setOpen] = useState(false);
  return (
    <View style={styles.container}>
      <View style={styles.chips}>
        {tags.map((tag, position) => (
          <View key={tag} style={[styles.chip, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text testID={listTestId(ENTRY_IDS.tagChip, position)} style={{ color: colors.text }}>
              {tag}
            </Text>
            {readOnly ? null : (
              <Pressable
                testID={listTestId(ENTRY_IDS.tagRemove, position)}
                role="button"
                aria-label={EN['tags.removeTag'](tag)}
                onPress={() => onChange(tags.filter((item) => item !== tag))}
              >
                <Icon name="close" size={REMOVE_ICON} color={colors.textMuted} />
              </Pressable>
            )}
          </View>
        ))}
      </View>
      {readOnly ? null : (
        <PaperButton testID="tags.choose" icon="label" mode="outlined" onPress={() => setOpen(true)}>
          {EN['tags.choose']}
        </PaperButton>
      )}
      <TagPickerDialog visible={open} selected={tags} onChange={onChange} onClose={() => setOpen(false)} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: SPACING.sm, alignItems: 'flex-start' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  chip: { flexDirection: 'row', alignItems: 'center', gap: SPACING.xs, borderWidth: StyleSheet.hairlineWidth, borderRadius: RADIUS.md, paddingHorizontal: SPACING.md, paddingVertical: SPACING.xs },
  label: { textAlign: 'left' },
  empty: { padding: SPACING.lg, fontSize: FONT_SIZE.body },
});

import { normalizeTag } from '@kodakiko/core';
import { useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Button as PaperButton, Dialog, FAB, Portal } from 'react-native-paper';
import { useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { IconAction } from '../../ui/IconAction';
import { ICONS } from '../../ui/icons';
import { TextField } from '../../ui/TextField';
import { useTheme } from '../../ui/theme';
import { FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { EMPTY_GALLERY_FILTER } from '../gallery/filter-model';
import { useIndexVersion } from '../gallery/hooks';
import { SettingsPage } from '../settings/settings-ui';
import { useDeviceSettings } from '../settings/use-device-settings';
import { moveTag, orderTags } from './tag-order';

type Editing =
  | { readonly kind: 'add' }
  | { readonly kind: 'rename'; readonly tag: string }
  | { readonly kind: 'delete'; readonly tag: string; readonly count: number }
  | null;

const SMALL_ICON = 20;
const MOVE_UP = -1;
const MOVE_DOWN = 1;

export function TagsScreen() {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const version = useIndexVersion();
  const [settings, update] = useDeviceSettings();
  const [editing, setEditing] = useState<Editing>(null);
  const [name, setName] = useState('');
  const [invalid, setInvalid] = useState(false);
  const tags = useMemo(
    () => orderTags(services.index.index()?.allTags() ?? [], settings.tagOrder),
    [services.index, version, settings.tagOrder],
  );
  const fullOrder = tags.map((item) => item.tag);

  const entriesWith = (tag: string): string[] =>
    (services.index.index()?.query({ ...EMPTY_GALLERY_FILTER, tags: [tag] }) ?? []).flatMap(
      (section) => [...section.entryIds],
    );

  const close = () => {
    setEditing(null);
    setName('');
    setInvalid(false);
  };

  const save = () => {
    if (editing === null || editing.kind === 'delete') return;
    const normalized = normalizeTag(name);
    if (normalized === null) {
      setInvalid(true);
      return;
    }
    if (editing.kind === 'add') {
      if (!fullOrder.includes(normalized)) update({ tagOrder: [...fullOrder, normalized] });
      close();
      return;
    }
    const from = editing.tag;
    services.entries
      .renameTag(entriesWith(from), from, normalized)
      .then(() => update({ tagOrder: fullOrder.map((tag) => (tag === from ? normalized : tag)) }))
      .catch(() => undefined);
    close();
  };

  return (
    <View style={styles.root}>
      <SettingsPage testID="tags.screen" title={EN['tags.title']}>
        {tags.length === 0 ? (
          <Text style={{ color: colors.textMuted }}>{EN['tags.noneYet']}</Text>
        ) : null}
        {tags.map((item, position) => (
          <View
            key={item.tag}
            testID={`tags.row.${position}`}
            style={[styles.row, { backgroundColor: colors.surface }]}
          >
            <View>
              <IconAction
                testID={`tags.up.${position}`}
                icon="ascending"
                label={EN['tags.moveUp']}
                tonal={false}
                size={SMALL_ICON}
                disabled={position === 0}
                onPress={() => update({ tagOrder: moveTag(fullOrder, item.tag, MOVE_UP) })}
              />
              <IconAction
                testID={`tags.down.${position}`}
                icon="descending"
                label={EN['tags.moveDown']}
                tonal={false}
                size={SMALL_ICON}
                disabled={position === tags.length - 1}
                onPress={() => update({ tagOrder: moveTag(fullOrder, item.tag, MOVE_DOWN) })}
              />
            </View>
            <View style={styles.texts}>
              <Text style={[styles.name, { color: colors.text }]}>{item.tag}</Text>
              <Text style={{ color: colors.textMuted }}>{EN['tags.count'](item.count)}</Text>
            </View>
            <IconAction
              testID={`tags.rename.${position}`}
              icon="edit"
              label={EN['tags.rename']}
              tonal={false}
              onPress={() => {
                setName(item.tag);
                setEditing({ kind: 'rename', tag: item.tag });
              }}
            />
            <IconAction
              testID={`tags.delete.${position}`}
              icon="trash"
              label={EN['tags.delete']}
              tonal={false}
              onPress={() => setEditing({ kind: 'delete', tag: item.tag, count: item.count })}
            />
          </View>
        ))}
      </SettingsPage>
      <FAB
        testID="tags.add"
        icon={ICONS.add}
        label={EN['tags.add']}
        style={styles.fab}
        onPress={() => setEditing({ kind: 'add' })}
      />
      <Portal>
        <Dialog visible={editing !== null} onDismiss={close}>
          {editing?.kind === 'delete' ? (
            <>
              <Dialog.Title>{EN['tags.delete']}</Dialog.Title>
              <Dialog.Content>
                <Text style={{ color: colors.text }}>
                  {EN['tags.deleteConfirm'](editing.tag, editing.count)}
                </Text>
              </Dialog.Content>
              <Dialog.Actions>
                <PaperButton onPress={close}>{EN['settings.cancel']}</PaperButton>
                <PaperButton
                  testID="tags.confirmDelete"
                  onPress={() => {
                    const tag = editing.tag;
                    services.entries
                      .deleteTag(entriesWith(tag), tag)
                      .then(() => update({ tagOrder: fullOrder.filter((item) => item !== tag) }))
                      .catch(() => undefined);
                    close();
                  }}
                >
                  {EN['tags.delete']}
                </PaperButton>
              </Dialog.Actions>
            </>
          ) : (
            <>
              <Dialog.Title>
                {editing?.kind === 'rename' ? EN['tags.rename'] : EN['tags.add']}
              </Dialog.Title>
              <Dialog.Content>
                <TextField
                  testID="tags.nameField"
                  label={EN['tags.name']}
                  value={name}
                  onChangeText={setName}
                  autoCapitalize="none"
                  onSubmit={save}
                  errorText={invalid ? EN['tags.invalid'] : null}
                />
              </Dialog.Content>
              <Dialog.Actions>
                <PaperButton onPress={close}>{EN['settings.cancel']}</PaperButton>
                <PaperButton testID="tags.save" onPress={save}>
                  {EN['tags.save']}
                </PaperButton>
              </Dialog.Actions>
            </>
          )}
        </Dialog>
      </Portal>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: RADIUS.md,
    paddingRight: SPACING.xs,
  },
  texts: { flex: 1, gap: SPACING.xs },
  name: { fontSize: FONT_SIZE.body, fontWeight: '600' },
  fab: { position: 'absolute', right: SPACING.lg, bottom: SPACING.lg },
});

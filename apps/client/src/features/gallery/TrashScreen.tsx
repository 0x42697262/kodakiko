import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { IconAction } from '../../ui/IconAction';
import { Screen } from '../../ui/Screen';
import { Spinner } from '../../ui/Spinner';
import { Body } from '../../ui/Typography';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { BulkActionsSheet } from '../entries/bulk-actions-sheet';
import { EntryGrid } from './entry-grid';
import { EMPTY_GALLERY_FILTER } from './filter-model';
import { useIndexStatus } from './hooks';
import { Sheet } from './sheet';
import { useSelection } from './use-selection';

const TRASH_FILTER = Object.freeze({ ...EMPTY_GALLERY_FILTER, view: 'trash' as const });

export function TrashScreen() {
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const status = useIndexStatus();
  const selection = useSelection();
  const [actionsOpen, setActionsOpen] = useState(false);
  const [acted, setActed] = useState(false);
  const trashDays = (services.config.get() ?? CONFIG_FALLBACK).trashDays;

  useFocusEffect(
    useCallback(
      () => () => {
        selection.clear();
        setActionsOpen(false);
      },
      [selection.clear],
    ),
  );

  function closeActions(): void {
    setActionsOpen(false);
    if (acted) selection.clear();
    setActed(false);
  }

  return (
    <Screen testID={GALLERY_IDS.trashScreen}>
      <View style={styles.header}>
        {selection.selecting ? (
          <Text testID={testId('gallery.selected')} style={[styles.title, { color: colors.text }]}>
            {EN['gallery.selected'](selection.selected.size)}
          </Text>
        ) : (
          <Text
            testID={testId('trash.title')}
            accessibilityRole="header"
            style={[styles.title, { color: colors.text }]}
          >
            {EN['trash.title']}
          </Text>
        )}
        <View style={styles.actions}>
          {selection.selecting ? (
            <>
              <Button
                testID={testId('gallery.cancelSelection')}
                label={EN['gallery.cancelSelection']}
                variant="secondary"
                onPress={selection.clear}
              />
              <Button
                testID={testId('gallery.actions')}
                label={EN['gallery.actions']}
                disabled={selection.selected.size === 0}
                onPress={() => setActionsOpen(true)}
              />
            </>
          ) : (
            <IconAction
              testID={testId('trash.back')}
              label={EN['trash.back']}
              onPress={() => router.back()}
              icon="back"
              tonal={false}
            />
          )}
        </View>
      </View>
      <Banner
        testID={testId('trash.retention')}
        kind="info"
        message={EN['trash.retention'](trashDays)}
      />
      {status === 'ready' ? (
        <EntryGrid
          filter={TRASH_FILTER}
          showOnThisDay={false}
          selection={selection}
          empty={
            <View style={styles.empty}>
              <Body testID={testId('trash.empty')} text={EN['trash.empty']} />
            </View>
          }
        />
      ) : (
        <View style={styles.empty}>
          <Spinner testID="gallery.opening.busy" />
          <Body testID={testId('gallery.opening')} text={EN['gallery.opening']} />
        </View>
      )}
      {actionsOpen ? (
        <Sheet testID={GALLERY_IDS.actionsSheet} onClose={closeActions}>
          <BulkActionsSheet
            ids={[...selection.selected]}
            mode="trash"
            onDone={() => setActed(true)}
          />
        </Sheet>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: SPACING.sm,
    flexWrap: 'wrap',
  },
  title: { fontSize: FONT_SIZE.title, fontWeight: '600' },
  actions: { flexDirection: 'row', gap: SPACING.sm },
  empty: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: SPACING.md },
});

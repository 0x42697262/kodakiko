import { useRouter } from '../../navigation/use-router';
import { useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { CALENDAR_IDS } from '../../keys/calendar-ids';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { IconAction } from '../../ui/IconAction';
import { Screen } from '../../ui/Screen';
import { Body } from '../../ui/Typography';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { BulkActionsSheet } from '../entries/bulk-actions-sheet';
import { EntryGrid } from '../gallery/entry-grid';
import { EMPTY_GALLERY_FILTER } from '../gallery/filter-model';
import { formatDayHeader } from '../gallery/format-day-header';
import { todayLocalDate } from '../gallery/hooks';
import { Sheet } from '../gallery/sheet';
import { useSelection } from '../gallery/use-selection';

type DayScreenProps = { readonly localDate: string };

export function DayScreen({ localDate }: DayScreenProps) {
  const router = useRouter();
  const { clock } = useAppContext();
  const { colors } = useTheme();
  const selection = useSelection();
  const [actionsOpen, setActionsOpen] = useState(false);
  const filter = useMemo(
    () => ({ ...EMPTY_GALLERY_FILTER, fromDate: localDate, toDate: localDate }),
    [localDate],
  );

  return (
    <Screen testID={CALENDAR_IDS.dayScreen}>
      <View style={styles.header}>
        {selection.selecting ? (
          <Text testID={testId('gallery.selected')} style={[styles.title, { color: colors.text }]}>
            {EN['gallery.selected'](selection.selected.size)}
          </Text>
        ) : (
          <Text
            testID={CALENDAR_IDS.dayTitle}
            accessibilityRole="header"
            style={[styles.title, { color: colors.text }]}
          >
            {formatDayHeader(localDate, todayLocalDate(clock.now()))}
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
              testID={testId('day.back')}
              label={EN['day.back']}
              onPress={() => router.back()}
              icon="back"
              tonal={false}
            />
          )}
        </View>
      </View>
      <EntryGrid
        filter={filter}
        showOnThisDay={false}
        selection={selection}
        empty={
          <View style={styles.empty}>
            <Body testID={testId('day.empty')} text={EN['day.empty']} />
            <IconAction
              testID={testId('day.back')}
              label={EN['day.back']}
              onPress={() => router.back()}
              icon="back"
              tonal={false}
            />
          </View>
        }
      />
      {actionsOpen ? (
        <Sheet testID={GALLERY_IDS.actionsSheet} onClose={() => setActionsOpen(false)}>
          <BulkActionsSheet ids={[...selection.selected]} mode="journal" onDone={selection.clear} />
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

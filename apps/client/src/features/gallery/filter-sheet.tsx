import { useState } from 'react';
import { Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { DisplayTab, GroupTab, UnderlineTabs } from './sheet-tabs';
import { useAppContext } from '../../composition/app-context';
import { FILTER_DATE_FIELD_MIN_WIDTH } from '../../constants/gallery-constants';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Hint } from '../../ui/Typography';
import { FONT_SIZE, MIN_TOUCH_TARGET, SPACING } from '../../ui/theme-constants';
import { Icon } from '../../ui/icons';
import { useDeviceSettings } from '../settings/use-device-settings';
import { useTheme } from '../../ui/theme';
import { TagPicker } from '../tags/tag-picker';
import { validateDateRange, type DateRangeProblem } from './filter-model';
import { reshuffleGallery, useGalleryFilter } from './hooks';
import { Sheet } from './sheet';

export function FilterSheet({ onClose }: { readonly onClose: () => void }) {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const filter = useGalleryFilter();
  const [tags, setTags] = useState<readonly string[]>(filter.tags);
  const [from, setFrom] = useState(filter.fromDate ?? '');
  const [to, setTo] = useState(filter.toDate ?? '');
  const [problem, setProblem] = useState<DateRangeProblem | null>(null);

  function apply(): void {
    const found = validateDateRange(from.trim(), to.trim());
    setProblem(found);
    if (found !== null) return;
    services.galleryFilter.set({
      ...services.galleryFilter.get(),
      tags,
      fromDate: from.trim() === '' ? null : from.trim(),
      toDate: to.trim() === '' ? null : to.trim(),
    });
    onClose();
  }

  function reset(): void {
    setTags([]);
    setFrom('');
    setTo('');
    setProblem(null);
  }

  const [settings, update] = useDeviceSettings();
  const [tab, setTab] = useState<SheetTab>('filter');
  const window = useWindowDimensions();
  const filters = settings.galleryFilters;
  const triRows: readonly { readonly key: keyof typeof filters; readonly label: string }[] = [
    { key: 'video', label: EN['sheet.hasVideo'] },
    { key: 'note', label: EN['sheet.hasNote'] },
    { key: 'multiple', label: EN['sheet.multiple'] },
    { key: 'shared', label: EN['sheet.shared'] },
    { key: 'pending', label: EN['sheet.pending'] },
    { key: 'favorite', label: EN['sheet.favorite'] },
  ];
  const sortRows: readonly {
    readonly key: typeof settings.gallerySort.key;
    readonly label: string;
  }[] = [
    { key: 'takenAt', label: EN['sort.takenAt'] },
    { key: 'noteLength', label: EN['sort.noteLength'] },
    { key: 'mediaCount', label: EN['sort.mediaCount'] },
    { key: 'random', label: EN['sort.random'] },
  ];
  return (
    <Sheet
      testID={GALLERY_IDS.filterSheet}
      onClose={onClose}
      header={
        <UnderlineTabs
          tabs={[
            { key: 'filter', label: EN['sheet.filter'] },
            { key: 'sort', label: EN['sheet.sort'] },
            { key: 'display', label: EN['sheet.display'] },
            { key: 'group', label: EN['sheet.group'] },
          ]}
          value={tab}
          onChange={setTab}
        />
      }
    >
      {tab === 'sort' ? (
        <View>
          {sortRows.map((row) => {
            const active = settings.gallerySort.key === row.key;
            return (
              <Pressable
                key={row.key}
                testID={`sheet.sort.${row.key}`}
                role="radio"
                aria-checked={active}
                style={styles.optionRow}
                onPress={() => {
                  if (row.key === 'random') reshuffleGallery();
                  update({
                    gallerySort: {
                      key: row.key,
                      descending: active
                        ? !settings.gallerySort.descending
                        : settings.gallerySort.descending,
                    },
                  });
                }}
              >
                <View style={styles.optionIcon}>
                  {active ? (
                    <Icon
                      name={
                        row.key === 'random'
                          ? 'random'
                          : settings.gallerySort.descending
                            ? 'descending'
                            : 'ascending'
                      }
                      size={OPTION_ICON_SIZE}
                      color={colors.primary}
                    />
                  ) : null}
                </View>
                <Text
                  style={{ color: active ? colors.primary : colors.text, fontSize: FONT_SIZE.body }}
                >
                  {row.label}
                </Text>
              </Pressable>
            );
          })}
        </View>
      ) : null}
      {tab === 'display' ? (
        <DisplayTab settings={settings} update={update} landscape={window.width > window.height} />
      ) : null}
      {tab === 'group' ? <GroupTab settings={settings} update={update} /> : null}
      {tab !== 'filter' ? null : (
        <>
          {triRows.map((row) => {
            const state = filters[row.key];
            return (
              <Pressable
                key={row.key}
                testID={`sheet.filter.${row.key}`}
                role="checkbox"
                aria-checked={state === 'include' ? true : state === 'exclude' ? 'mixed' : false}
                style={styles.optionRow}
                onPress={() =>
                  update({ galleryFilters: { ...filters, [row.key]: NEXT_TRI_STATE[state] } })
                }
              >
                <View style={styles.optionIcon}>
                  <Icon
                    name={
                      state === 'include'
                        ? 'triInclude'
                        : state === 'exclude'
                          ? 'triExclude'
                          : 'triOff'
                    }
                    size={OPTION_ICON_SIZE}
                    color={
                      state === 'off'
                        ? colors.textMuted
                        : state === 'include'
                          ? colors.primary
                          : colors.danger
                    }
                  />
                </View>
                <Text style={{ color: colors.text, fontSize: FONT_SIZE.body }}>{row.label}</Text>
              </Pressable>
            );
          })}
          <Text style={[styles.subtitle, { color: colors.textMuted }]}>
            {EN['sheet.tagsAndDates']}
          </Text>
          <TagPicker tags={tags} onChange={setTags} />
          <View style={styles.row}>
            <View style={styles.field}>
              <TextField
                testID={testId('filter.from')}
                label={EN['filter.from']}
                placeholder={EN['filter.datePlaceholder']}
                value={from}
                onChangeText={setFrom}
              />
            </View>
            <View style={styles.field}>
              <TextField
                testID={testId('filter.to')}
                label={EN['filter.to']}
                placeholder={EN['filter.datePlaceholder']}
                value={to}
                onChangeText={setTo}
              />
            </View>
          </View>
          {problem === null ? null : <Hint testID={testId(problem)} text={EN[problem]} />}
          <View style={styles.row}>
            <Button testID={testId('filter.apply')} label={EN['filter.apply']} onPress={apply} />
            <Button
              testID={testId('filter.reset')}
              label={EN['filter.reset']}
              variant="secondary"
              onPress={reset}
            />
          </View>
        </>
      )}
    </Sheet>
  );
}

type SheetTab = 'filter' | 'sort' | 'display' | 'group';
type TriState = 'off' | 'include' | 'exclude';
const NEXT_TRI_STATE: Readonly<Record<TriState, TriState>> = {
  off: 'include',
  include: 'exclude',
  exclude: 'off',
};
const OPTION_ICON_SIZE = 24;

const styles = StyleSheet.create({
  optionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.md,
    minHeight: MIN_TOUCH_TARGET,
  },
  optionIcon: { width: OPTION_ICON_SIZE },
  subtitle: { fontSize: FONT_SIZE.caption, fontWeight: '600', paddingTop: SPACING.md },
  title: { fontSize: FONT_SIZE.headline, fontWeight: '600' },
  row: { flexDirection: 'row', gap: SPACING.sm, flexWrap: 'wrap' },
  field: { flex: 1, minWidth: FILTER_DATE_FIELD_MIN_WIDTH },
});

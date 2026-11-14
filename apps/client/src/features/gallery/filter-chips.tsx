import { StyleSheet, Text, View } from 'react-native';
import { Touchable } from '../../ui/Touchable';
import { useAppContext } from '../../composition/app-context';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { listTestId, testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import {
  BORDER_WIDTH,
  FONT_SIZE,
  MIN_TOUCH_TARGET,
  RADIUS,
  SPACING,
} from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { EMPTY_GALLERY_FILTER, isFilterActive } from './filter-model';
import { useGalleryFilter } from './hooks';

type Chip = { readonly key: string; readonly label: string; readonly remove: () => void };

function dateLabel(from: string | null, to: string | null): string | null {
  if (from !== null && to !== null) return EN['gallery.dateRange'](from, to);
  if (from !== null) return EN['gallery.dateFrom'](from);
  if (to !== null) return EN['gallery.dateTo'](to);
  return null;
}

export function FilterChips({ onClearAll }: { readonly onClearAll: () => void }) {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const filter = useGalleryFilter();
  if (!isFilterActive(filter)) return null;
  const store = services.galleryFilter;
  const chips: Chip[] = filter.tags.map((tag) => ({
    key: `tag:${tag}`,
    label: tag,
    remove: () =>
      store.set({ ...store.get(), tags: store.get().tags.filter((name) => name !== tag) }),
  }));
  const dates = dateLabel(filter.fromDate, filter.toDate);
  if (dates !== null) {
    chips.push({
      key: 'dates',
      label: dates,
      remove: () => store.set({ ...store.get(), fromDate: null, toDate: null }),
    });
  }
  if (filter.text.trim() !== '') {
    chips.push({
      key: 'text',
      label: EN['gallery.searchChip'](filter.text.trim()),
      remove: () => store.set({ ...store.get(), text: '' }),
    });
  }
  return (
    <View style={styles.row}>
      {chips.map((chip, position) => (
        <View
          key={chip.key}
          style={[styles.chip, { backgroundColor: colors.surface, borderColor: colors.border }]}
        >
          <Text
            testID={listTestId(GALLERY_IDS.filterChip, position)}
            style={[styles.text, { color: colors.text }]}
          >
            {chip.label}
          </Text>
          <Touchable
            testID={listTestId(GALLERY_IDS.filterChipRemove, position)}
            accessibilityRole="button"
            accessibilityLabel={EN['gallery.removeFilter'](chip.label)}
            onPress={chip.remove}
            style={styles.remove}
          >
            <Text style={{ color: colors.textMuted }}>{EN['gallery.removeGlyph']}</Text>
          </Touchable>
        </View>
      ))}
      <Button
        testID={testId('gallery.clearAll')}
        label={EN['gallery.clearAll']}
        variant="secondary"
        onPress={() => {
          store.set(EMPTY_GALLERY_FILTER);
          onClearAll();
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm, alignItems: 'center' },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: MIN_TOUCH_TARGET,
    paddingHorizontal: SPACING.md,
    borderRadius: RADIUS.md,
    borderWidth: BORDER_WIDTH,
  },
  text: { fontSize: FONT_SIZE.body },
  remove: { paddingLeft: SPACING.sm, minHeight: MIN_TOUCH_TARGET, justifyContent: 'center' },
});

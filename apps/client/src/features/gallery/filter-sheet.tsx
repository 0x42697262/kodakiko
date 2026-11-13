import { TAGS_PER_ENTRY_MAX } from '@kodakiko/contract';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { FILTER_DATE_FIELD_MIN_WIDTH } from '../../constants/gallery-constants';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Hint } from '../../ui/Typography';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { TagInput } from '../tags/tag-input';
import { validateDateRange, type DateRangeProblem } from './filter-model';
import { useGalleryFilter } from './hooks';
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

  return (
    <Sheet testID={GALLERY_IDS.filterSheet} onClose={onClose}>
      <Text testID={testId('filter.title')} style={[styles.title, { color: colors.text }]}>
        {EN['filter.title']}
      </Text>
      <TagInput
        tags={tags}
        onChange={setTags}
        suggestions={(prefix) => services.tagSuggestions.suggest(prefix)}
        maxTags={TAGS_PER_ENTRY_MAX}
      />
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
    </Sheet>
  );
}

const styles = StyleSheet.create({
  title: { fontSize: FONT_SIZE.headline, fontWeight: '600' },
  row: { flexDirection: 'row', gap: SPACING.sm, flexWrap: 'wrap' },
  field: { flex: 1, minWidth: FILTER_DATE_FIELD_MIN_WIDTH },
});

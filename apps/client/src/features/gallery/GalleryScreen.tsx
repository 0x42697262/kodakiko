import { useFocusEffect, useRouter, type Href } from 'expo-router';
import { useCallback, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useAccountState, useAppContext } from '../../composition/app-context';
import { FAB_GLYPH_SIZE, FAB_SIZE } from '../../constants/gallery-constants';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { APP_ROUTES } from '../../keys/routes';
import { TEST_IDS, testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { Spinner } from '../../ui/Spinner';
import { TextField } from '../../ui/TextField';
import { Body } from '../../ui/Typography';
import { BORDER_WIDTH, FONT_SIZE, MIN_TOUCH_TARGET, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { BulkActionsSheet } from '../entries/bulk-actions-sheet';
import { SavedBanner } from '../entries/SavedBanner';
import { SyncStatusBanner } from '../sync/sync-status-banner';
import { EntryGrid } from './entry-grid';
import { GalleryNoticeBanner } from './gallery-notice';
import { EMPTY_GALLERY_FILTER, isFilterActive } from './filter-model';
import { FilterChips } from './filter-chips';
import { FilterSheet } from './filter-sheet';
import { useGalleryFilter, useIndexStatus, useOnThisDay } from './hooks';
import { Sheet } from './sheet';
import { useSelection, type Selection } from './use-selection';

type Overlay = 'none' | 'filter' | 'actions' | 'more';

function SelectionHeader({ selection, onActions }: { readonly selection: Selection; readonly onActions: () => void }) {
  const { colors } = useTheme();
  return (
    <View style={styles.header}>
      <Text testID={testId('gallery.selected')} style={[styles.title, { color: colors.text }]}>
        {EN['gallery.selected'](selection.selected.size)}
      </Text>
      <View style={styles.actions}>
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
          onPress={onActions}
        />
      </View>
    </View>
  );
}

export function GalleryScreen() {
  const router = useRouter();
  const { services } = useAppContext();
  const state = useAccountState();
  const { colors } = useTheme();
  const status = useIndexStatus();
  const filter = useGalleryFilter();
  const onThisDay = useOnThisDay();
  const selection = useSelection();
  const [overlay, setOverlay] = useState<Overlay>('none');
  const [searching, setSearching] = useState(filter.text !== '');
  const [acted, setActed] = useState(false);
  const active = isFilterActive(filter);
  const username = state.kind === 'unlocked' ? state.username : '';

  useFocusEffect(
    useCallback(() => () => {
      selection.clear();
      setOverlay('none');
    }, [selection.clear]),
  );

  function closeActions(): void {
    setOverlay('none');
    if (acted) selection.clear();
    setActed(false);
  }

  const empty = active ? (
    <View style={styles.empty}>
      <Body testID={testId('gallery.noMatches')} text={EN['gallery.noMatches']} />
      <Button
        testID={testId('gallery.clearFilters')}
        label={EN['gallery.clearFilters']}
        variant="secondary"
        onPress={() => {
          services.galleryFilter.set(EMPTY_GALLERY_FILTER);
          setSearching(false);
        }}
      />
    </View>
  ) : (
    <View style={styles.empty}>
      <Body testID={testId('gallery.empty')} text={EN['gallery.empty']} />
    </View>
  );

  return (
    <Screen testID={GALLERY_IDS.screen}>
      {selection.selecting ? (
        <SelectionHeader selection={selection} onActions={() => setOverlay('actions')} />
      ) : (
        <View style={styles.header}>
          <Text testID={testId('gallery.title')} accessibilityRole="header" style={[styles.title, { color: colors.text }]}>
            {EN['gallery.title']}
          </Text>
          <View style={styles.actions}>
            <Button
              testID={testId('gallery.search')}
              label={EN['gallery.search']}
              variant="secondary"
              onPress={() => {
                if (searching) services.galleryFilter.set({ ...services.galleryFilter.get(), text: '' });
                setSearching(!searching);
              }}
            />
            <Pressable
              testID={testId('gallery.filter')}
              accessibilityRole="button"
              accessibilityLabel={active ? EN['gallery.filterActive'] : EN['gallery.filter']}
              onPress={() => setOverlay('filter')}
              style={[styles.iconButton, { backgroundColor: colors.surface, borderColor: colors.border }]}
            >
              <Text style={[styles.buttonText, { color: colors.text }]}>{EN['gallery.filter']}</Text>
              {active ? (
                <Text testID={GALLERY_IDS.filterDot} style={{ color: colors.primary }}>
                  {EN['gallery.filterDot']}
                </Text>
              ) : null}
            </Pressable>
            <Pressable
              testID={testId('gallery.more')}
              accessibilityRole="button"
              accessibilityLabel={EN['gallery.more']}
              onPress={() => setOverlay('more')}
              style={[styles.iconButton, { backgroundColor: colors.surface, borderColor: colors.border }]}
            >
              <Text style={[styles.buttonText, { color: colors.text }]}>{EN['gallery.moreGlyph']}</Text>
            </Pressable>
          </View>
        </View>
      )}
      <View style={styles.accountRow}>
        <Text testID="home.signedInAs" style={[styles.account, { color: colors.textMuted }]}>
          {EN['home.signedInAs'](username)}
        </Text>
        <Button
          testID="home.signOut"
          label={EN['home.signOut']}
          variant="secondary"
          onPress={() => {
            services.auth.signOut().catch(() => undefined);
          }}
        />
      </View>
      {searching && !selection.selecting ? (
        <TextField
          testID={testId('gallery.searchField')}
          label={EN['gallery.searchField']}
          showLabel={false}
          placeholder={EN['gallery.searchField']}
          value={filter.text}
          onChangeText={(text) => services.galleryFilter.set({ ...services.galleryFilter.get(), text })}
        />
      ) : null}
      <SyncStatusBanner />
      <SavedBanner />
      <GalleryNoticeBanner />
      <FilterChips onClearAll={() => setSearching(false)} />
      {status === 'ready' ? (
        <EntryGrid
          filter={filter}
          showOnThisDay={!active && onThisDay.length > 0}
          selection={selection}
          empty={empty}
        />
      ) : (
        <View style={styles.empty}>
          <Spinner testID="gallery.opening.busy" />
          <Body testID={testId('gallery.opening')} text={EN['gallery.opening']} />
        </View>
      )}
      {selection.selecting ? null : (
        <Pressable
          testID={TEST_IDS.galleryFab}
          accessibilityRole="button"
          accessibilityLabel={EN['gallery.capture']}
          onPress={() => router.push(APP_ROUTES.capture as Href)}
          style={[styles.fab, { backgroundColor: colors.primary }]}
        >
          <Text style={[styles.fabGlyph, { color: colors.onPrimary }]}>{EN['gallery.captureGlyph']}</Text>
        </Pressable>
      )}
      {overlay === 'filter' ? <FilterSheet onClose={() => setOverlay('none')} /> : null}
      {overlay === 'more' ? (
        <Sheet testID={GALLERY_IDS.moreMenu} onClose={() => setOverlay('none')}>
          <Button
            testID={testId('lock.lockNow')}
            label={EN['lock.lockNow']}
            variant="secondary"
            onPress={() => {
              setOverlay('none');
              services.lock.lockNow().catch(() => undefined);
            }}
          />
          <Button
            testID={testId('gallery.trash')}
            label={EN['gallery.trash']}
            variant="secondary"
            onPress={() => {
              setOverlay('none');
              router.push(APP_ROUTES.trash as Href);
            }}
          />
        </Sheet>
      ) : null}
      {overlay === 'actions' ? (
        <Sheet testID={GALLERY_IDS.actionsSheet} onClose={closeActions}>
          <BulkActionsSheet ids={[...selection.selected]} mode="journal" onDone={() => setActed(true)} />
        </Sheet>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: SPACING.sm, flexWrap: 'wrap' },
  title: { fontSize: FONT_SIZE.title, fontWeight: '600' },
  account: { fontSize: FONT_SIZE.caption, flexShrink: 1 },
  accountRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: SPACING.sm },
  actions: { flexDirection: 'row', gap: SPACING.sm, alignItems: 'center' },
  iconButton: {
    flexDirection: 'row',
    gap: SPACING.xs,
    minHeight: MIN_TOUCH_TARGET,
    paddingHorizontal: SPACING.md,
    borderRadius: RADIUS.sm,
    borderWidth: BORDER_WIDTH,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: { fontSize: FONT_SIZE.headline, fontWeight: '600' },
  empty: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: SPACING.md },
  fab: {
    position: 'absolute',
    right: SPACING.xl,
    bottom: SPACING.xl,
    width: FAB_SIZE,
    height: FAB_SIZE,
    borderRadius: FAB_SIZE,
    alignItems: 'center',
    justifyContent: 'center',
  },
  fabGlyph: { fontSize: FAB_GLYPH_SIZE, fontWeight: '600' },
});

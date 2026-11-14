import { useFocusEffect, type Href } from 'expo-router';
import { useRouter } from '../../navigation/use-router';
import { useCallback, useMemo, useState, useSyncExternalStore } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { FAB } from 'react-native-paper';
import { useAccountState, useAppContext } from '../../composition/app-context';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { APP_ROUTES, entryRoute } from '../../keys/routes';
import { TEST_IDS, testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { AppBar } from '../../ui/AppBar';
import { setBarsHidden, useBarsHidden } from '../../ui/bars-store';
import { Button } from '../../ui/Button';
import { ICONS } from '../../ui/icons';
import { Screen } from '../../ui/Screen';
import { Spinner } from '../../ui/Spinner';
import { TextField } from '../../ui/TextField';
import { Body } from '../../ui/Typography';
import { BORDER_WIDTH, FONT_SIZE, MIN_TOUCH_TARGET, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { BulkActionsSheet } from '../entries/bulk-actions-sheet';
import { SyncStatusBanner } from '../sync/sync-status-banner';
import { EntryGrid } from './entry-grid';
import { EMPTY_GALLERY_FILTER, isFilterActive } from './filter-model';
import { FilterChips } from './filter-chips';
import { FilterSheet } from './filter-sheet';
import { galleryAttributes } from './gallery-arrange';
import { displayOrder } from './gallery-items';
import { useArrangedSections, useGalleryFilter, useIndexStatus, useOnThisDay } from './hooks';
import { TagTabs } from './tag-tabs';
import { Sheet } from './sheet';
import { useSelection, type Selection } from './use-selection';

type Overlay = 'none' | 'filter' | 'actions' | 'more';


function SelectionHeader({ selection, order, onActions }: { readonly selection: Selection; readonly order: readonly string[]; readonly onActions: () => void }) {
  const { colors } = useTheme();
  return (
    <AppBar
      title={EN['gallery.selected'](selection.selected.size)}
      titleNode={
        <Text testID={testId('gallery.selected')} style={[styles.title, { color: colors.text }]}>
          {EN['gallery.selected'](selection.selected.size)}
        </Text>
      }
      backTestID={testId('gallery.cancelSelection')}
      backIcon="close"
      backLabel={EN['gallery.cancelSelection']}
      onBack={selection.clear}
      actions={[
        { key: 'selectAll', icon: 'selectAll', label: EN['gallery.selectAll'], testID: 'gallery.selectAll', onPress: () => selection.selectAll(order) },
        { key: 'actions', icon: 'overflow', label: EN['gallery.actions'], testID: testId('gallery.actions'), onPress: onActions },
      ]}
    />
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
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const attributes = galleryAttributes(settings.galleryFilters, false);
  const active = isFilterActive(filter) || Object.values(attributes).some((state) => state !== 'off');
  const sections = useArrangedSections(filter);
  const barsHidden = useBarsHidden();
  const order = useMemo(() => displayOrder(sections), [sections]);

  function openRandom(): void {
    if (order.length === 0) return;
    const entryId = order[Math.floor(Math.random() * order.length)];
    if (entryId !== undefined) router.push(entryRoute(entryId) as Href);
  }
  const username = state.kind === 'unlocked' ? state.username : '';

  useFocusEffect(
    useCallback(() => () => {
      setBarsHidden(false);
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
      {barsHidden && !selection.selecting ? null : selection.selecting ? (
        <SelectionHeader selection={selection} order={order} onActions={() => setOverlay('actions')} />
      ) : (
        <AppBar
          title={EN['gallery.title']}
          titleNode={
            <View>
              <View style={styles.titleRow}>
                <Text testID={testId('gallery.title')} accessibilityRole="header" style={[styles.title, { color: colors.text }]}>
                  {EN['gallery.title']}
                </Text>
                {settings.galleryShowCounts ? (
                  <Text testID="gallery.count" style={[styles.countPill, { color: colors.text, backgroundColor: colors.surface }]}>
                    {order.length}
                  </Text>
                ) : null}
              </View>
              <Text testID="home.signedInAs" numberOfLines={1} style={[styles.account, { color: colors.textMuted }]}>
                {EN['home.signedInAs'](username)}
              </Text>
            </View>
          }
          actions={[
            {
              key: 'search',
              icon: searching ? 'close' : 'search',
              label: EN['gallery.search'],
              testID: testId('gallery.search'),
              onPress: () => {
                if (searching) services.galleryFilter.set({ ...services.galleryFilter.get(), text: '' });
                setSearching(!searching);
              },
            },
            { key: 'filter', icon: 'filter', label: active ? EN['gallery.filterActive'] : EN['gallery.filter'], testID: testId('gallery.filter'), active, onPress: () => setOverlay('filter') },
          ]}
          menuTestID={testId('gallery.more')}
          menu={[
            { key: 'random', label: EN['gallery.random'], testID: 'gallery.random', onPress: openRandom },
            { key: 'lockNow', label: EN['lock.lockNow'], testID: testId('lock.lockNow'), onPress: () => services.lock.lockNow().catch(() => undefined) },
            { key: 'trash', label: EN['gallery.trash'], testID: testId('gallery.trash'), onPress: () => router.push(APP_ROUTES.trash as Href) },
            { key: 'signOut', label: EN['home.signOut'], testID: 'home.signOut', onPress: () => services.auth.signOut().catch(() => undefined) },
          ]}
        />
      )}
      {barsHidden ? null : <TagTabs />}
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
        <FAB
          testID={TEST_IDS.galleryFab}
          icon={ICONS.capture}
          label={EN['gallery.new']}
          accessibilityLabel={EN['gallery.capture']}
          onPress={() => router.push(APP_ROUTES.capture as Href)}
          style={styles.fab}
        />
      )}
      {overlay === 'filter' ? <FilterSheet onClose={() => setOverlay('none')} /> : null}
      {overlay === 'actions' ? (
        <Sheet testID={GALLERY_IDS.actionsSheet} onClose={closeActions}>
          <BulkActionsSheet ids={[...selection.selected]} mode="journal" onDone={() => setActed(true)} />
        </Sheet>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm },
  countPill: { fontSize: FONT_SIZE.caption, paddingHorizontal: SPACING.sm, borderRadius: SPACING.md, overflow: 'hidden' },
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
  fab: { position: 'absolute', right: SPACING.lg, bottom: SPACING.lg },
});

import { FlashList } from '@shopify/flash-list';
import { useRouter, type Href } from 'expo-router';
import { useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { scrollDirectionHides, setBarsHidden } from '../../ui/bars-store';
import { StyleSheet, Text, useWindowDimensions, View, type LayoutChangeEvent } from 'react-native';
import type { GalleryFilter } from '@kodakiko/core';
import { useAppContext } from '../../composition/app-context';
import { TILE_GAP } from '../../constants/gallery-constants';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { entryRoute } from '../../keys/routes';
import { listTestId } from '../../keys/test-ids';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { formatDayHeader } from './format-day-header';
import { columnsFor } from './gallery-arrange';
import { buildGalleryItems, displayOrder, type GalleryItem } from './gallery-items';
import { todayLocalDate, useArrangedSections } from './hooks';
import { OnThisDayCard } from './on-this-day-card';
import { Tile } from './tile';
import type { Selection } from './use-selection';

type EntryGridProps = {
  readonly filter: GalleryFilter;
  readonly showOnThisDay: boolean;
  readonly selection: Selection;
  readonly empty: ReactNode;
};

function itemKey(item: GalleryItem): string {
  if (item.type === 'onThisDay') return item.type;
  if (item.type === 'header') return `${item.type}:${item.localDate}`;
  return `${item.type}:${item.firstPosition}`;
}

export function EntryGrid({ filter, showOnThisDay, selection, empty }: EntryGridProps) {
  const router = useRouter();
  const { clock, services } = useAppContext();
  const { colors } = useTheme();
  const window = useWindowDimensions();
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const [width, setWidth] = useState(0);
  const lastY = useRef(0);
  const sections = useArrangedSections(filter);
  const list = settings.galleryDisplayMode === 'list';
  const columns = list ? 1 : columnsFor(width, window.height, settings.galleryColumnsPortrait, settings.galleryColumnsLandscape);
  const display = useMemo(
    () => ({ mode: settings.galleryDisplayMode, badges: settings.galleryBadges, hideNotePreviews: settings.hideNotePreviews }),
    [settings.galleryDisplayMode, settings.galleryBadges, settings.hideNotePreviews],
  );
  const items = useMemo(
    () => buildGalleryItems(sections, columns, showOnThisDay),
    [sections, columns, showOnThisDay],
  );
  const order = useMemo(() => displayOrder(sections), [sections]);
  const headerPositions = useMemo(
    () => new Map(sections.map((section, position) => [section.localDate, position])),
    [sections],
  );
  const tileSize = width === 0 ? 0 : Math.floor((width - TILE_GAP * (columns - 1)) / columns);
  const today = todayLocalDate(clock.now());

  function onLayout(event: LayoutChangeEvent): void {
    setWidth(event.nativeEvent.layout.width);
  }

  function renderItem({ item }: { readonly item: GalleryItem }) {
    if (item.type === 'onThisDay') return <OnThisDayCard />;
    if (item.type === 'header') {
      return (
        <Text
          testID={listTestId(GALLERY_IDS.dayHeader, headerPositions.get(item.localDate) ?? 0)}
          accessibilityRole="header"
          style={[styles.header, { color: colors.text }]}
        >
          {formatDayHeader(item.localDate, today)}
        </Text>
      );
    }
    return (
      <View style={styles.row}>
        {item.entryIds.map((entryId, offset) => (
          <Tile
            key={entryId}
            entryId={entryId}
            position={item.firstPosition + offset}
            size={list ? width : tileSize}
            display={display}
            selecting={selection.selecting}
            selected={selection.selected.has(entryId)}
            onPress={(id, modifiers) => {
              if (!selection.tap(id, modifiers, order)) router.push(entryRoute(id) as Href);
            }}
            onLongPress={(id) => {
              if (!selection.selecting) selection.start(id);
            }}
          />
        ))}
      </View>
    );
  }

  return (
    <View style={styles.container} onLayout={onLayout}>
      {sections.length === 0 && !showOnThisDay ? (
        empty
      ) : width === 0 ? null : (
        <FlashList
          data={items}
          renderItem={renderItem}
          keyExtractor={itemKey}
          getItemType={(item) => item.type}
          extraData={{ selection, tileSize, display }}
          onScroll={(event) => {
            const y = event.nativeEvent.contentOffset.y;
            const hides = scrollDirectionHides(lastY.current, y);
            if (hides !== null) {
              lastY.current = y;
              if (settings.hideBarsOnScroll) setBarsHidden(hides);
            }
          }}
          scrollEventThrottle={SCROLL_THROTTLE_MS}
          ListFooterComponent={sections.length === 0 ? <>{empty}</> : null}
        />
      )}
    </View>
  );
}

const SCROLL_THROTTLE_MS = 16;

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: { fontSize: FONT_SIZE.headline, fontWeight: '600', paddingVertical: SPACING.sm },
  row: { flexDirection: 'row', gap: TILE_GAP, marginBottom: TILE_GAP },
});

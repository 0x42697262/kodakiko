import { FlashList } from '@shopify/flash-list';
import { useRouter, type Href } from 'expo-router';
import { useMemo, useState, type ReactNode } from 'react';
import { StyleSheet, Text, View, type LayoutChangeEvent } from 'react-native';
import type { GalleryFilter } from '@kodakiko/core';
import { useAppContext } from '../../composition/app-context';
import { TILE_GAP } from '../../constants/gallery-constants';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { entryRoute } from '../../keys/routes';
import { listTestId } from '../../keys/test-ids';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { formatDayHeader } from './format-day-header';
import { buildGalleryItems, displayOrder, gridColumns, type GalleryItem } from './gallery-items';
import { todayLocalDate, useGallerySections } from './hooks';
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
  const { clock } = useAppContext();
  const { colors } = useTheme();
  const [width, setWidth] = useState(0);
  const sections = useGallerySections(filter);
  const columns = gridColumns(width);
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
            size={tileSize}
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
          extraData={{ selection, tileSize }}
          ListFooterComponent={sections.length === 0 ? <>{empty}</> : null}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: { fontSize: FONT_SIZE.headline, fontWeight: '600', paddingVertical: SPACING.sm },
  row: { flexDirection: 'row', gap: TILE_GAP, marginBottom: TILE_GAP },
});

import { useMemo } from 'react';
import { Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { REMOVE_BUTTON_RADIUS, REMOVE_BUTTON_SIZE, TRAY_TILE_SIZE } from '../../constants/capture-constants';
import { CAPTURE_IDS } from '../../keys/capture-keys';
import { TEST_IDS, listTestId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Spinner } from '../../ui/Spinner';
import { RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import type { ImportedMedia } from '../../services/media/import-service';
import { thumbnailDataUri } from './thumbnail-uri';

type TrayStripProps = {
  readonly items: readonly ImportedMedia[];
  readonly pending: number;
  readonly onRemove: (mediaId: string) => void;
};

function TrayTile({
  item,
  position,
  onRemove,
}: {
  readonly item: ImportedMedia;
  readonly position: number;
  readonly onRemove: (mediaId: string) => void;
}) {
  const { colors } = useTheme();
  const uri = useMemo(() => thumbnailDataUri(item.thumbnailBytes), [item.thumbnailBytes]);
  return (
    <View testID={listTestId(TEST_IDS.captureTrayItem, position)} style={styles.tile}>
      <Image source={{ uri }} style={styles.image} />
      <Pressable
        testID={listTestId(CAPTURE_IDS.removeItem, position)}
        accessibilityRole="button"
        accessibilityLabel={EN['capture.removeItem'](position + 1)}
        onPress={() => onRemove(item.mediaId)}
        style={[styles.remove, { backgroundColor: colors.surface, borderColor: colors.border }]}
      >
        <Text style={{ color: colors.text }}>{EN['capture.removeGlyph']}</Text>
      </Pressable>
    </View>
  );
}

export function TrayStrip({ items, pending, onRemove }: TrayStripProps) {
  return (
    <ScrollView horizontal contentContainerStyle={styles.strip} showsHorizontalScrollIndicator={false}>
      {items.map((item, position) => (
        <TrayTile key={item.mediaId} item={item} position={position} onRemove={onRemove} />
      ))}
      {Array.from({ length: pending }, (_, position) => (
        <View key={`pending-${position}`} style={styles.tile}>
          <Spinner testID={listTestId(CAPTURE_IDS.importing, position)} />
        </View>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  strip: { gap: SPACING.sm, alignItems: 'center' },
  tile: {
    width: TRAY_TILE_SIZE,
    height: TRAY_TILE_SIZE,
    borderRadius: RADIUS.sm,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  image: { width: TRAY_TILE_SIZE, height: TRAY_TILE_SIZE },
  remove: {
    position: 'absolute',
    top: SPACING.xs,
    right: SPACING.xs,
    width: REMOVE_BUTTON_SIZE,
    height: REMOVE_BUTTON_SIZE,
    borderRadius: REMOVE_BUTTON_RADIUS,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
});

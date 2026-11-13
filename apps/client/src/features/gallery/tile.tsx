import { useState } from 'react';
import { Pressable, StyleSheet, Text, View, type GestureResponderEvent } from 'react-native';
import {
  BADGE_OFFSET,
  CHECKBOX_SIZE,
  PENDING_DOT_SIZE,
  SELECTED_BORDER_WIDTH,
} from '../../constants/gallery-constants';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { listTestId, TEST_IDS } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { BORDER_WIDTH, FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { useIndexedEntry } from './hooks';
import { Thumbnail } from './thumbnail';

export type TileModifiers = { readonly toggle: boolean; readonly range: boolean };

type ModifierEvent = {
  readonly nativeEvent?: {
    readonly ctrlKey?: boolean;
    readonly metaKey?: boolean;
    readonly shiftKey?: boolean;
  };
};

export function tileModifiers(event: GestureResponderEvent | ModifierEvent): TileModifiers {
  const native = (event as ModifierEvent).nativeEvent;
  return {
    toggle: native?.ctrlKey === true || native?.metaKey === true,
    range: native?.shiftKey === true,
  };
}

type TileProps = {
  readonly entryId: string;
  readonly position: number;
  readonly size: number;
  readonly selecting: boolean;
  readonly selected: boolean;
  readonly onPress: (entryId: string, modifiers: TileModifiers) => void;
  readonly onLongPress: (entryId: string) => void;
};

export function Tile({ entryId, position, size, selecting, selected, onPress, onLongPress }: TileProps) {
  const { colors } = useTheme();
  const entry = useIndexedEntry(entryId);
  const [hovered, setHovered] = useState(false);
  if (entry === null) return <View style={{ width: size, height: size }} />;
  const showCheckbox = selecting || hovered;
  return (
    <Pressable
      testID={listTestId(TEST_IDS.galleryTile, position)}
      accessibilityRole="button"
      accessibilityState={{ selected }}
      onPress={(event) => onPress(entryId, tileModifiers(event))}
      onLongPress={() => onLongPress(entryId)}
      onHoverIn={() => setHovered(true)}
      onHoverOut={() => setHovered(false)}
      style={{ width: size, height: size }}
    >
      <Thumbnail
        entryId={entryId}
        mediaId={entry.coverMediaId}
        size={size}
        unreadable={entry.unreadable}
      />
      {selected ? (
        <View pointerEvents="none" style={[styles.selectedFrame, { borderColor: colors.primary }]} />
      ) : null}
      {entry.mediaCount > 1 ? (
        <Text style={[styles.badge, styles.topRight, { color: colors.onPrimary, backgroundColor: colors.text }]}>
          {EN['gallery.moreMedia'](entry.mediaCount - 1)}
        </Text>
      ) : null}
      {entry.hasVideo ? (
        <Text style={[styles.badge, styles.bottomLeft, { color: colors.onPrimary, backgroundColor: colors.text }]}>
          {EN['gallery.playGlyph']}
        </Text>
      ) : null}
      {entry.pending ? (
        <View
          testID={listTestId(TEST_IDS.galleryTilePending, position)}
          accessibilityLabel={EN['gallery.notSynced']}
          style={[styles.dot, styles.bottomRight, { backgroundColor: colors.primary }]}
        />
      ) : null}
      {showCheckbox ? (
        <View
          testID={listTestId(GALLERY_IDS.tileCheckbox, position)}
          accessibilityLabel={EN['gallery.selectEntry']}
          style={[
            styles.checkbox,
            styles.topLeft,
            { borderColor: colors.onPrimary, backgroundColor: selected ? colors.primary : colors.surface },
          ]}
        >
          {selected ? <Text style={{ color: colors.onPrimary }}>{EN['gallery.checkGlyph']}</Text> : null}
        </View>
      ) : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  badge: {
    position: 'absolute',
    fontSize: FONT_SIZE.caption,
    paddingHorizontal: SPACING.xs,
    borderRadius: RADIUS.sm,
    overflow: 'hidden',
  },
  topRight: { top: BADGE_OFFSET, right: BADGE_OFFSET },
  topLeft: { top: BADGE_OFFSET, left: BADGE_OFFSET },
  bottomLeft: { bottom: BADGE_OFFSET, left: BADGE_OFFSET },
  bottomRight: { bottom: BADGE_OFFSET, right: BADGE_OFFSET },
  dot: { position: 'absolute', width: PENDING_DOT_SIZE, height: PENDING_DOT_SIZE, borderRadius: PENDING_DOT_SIZE },
  selectedFrame: { ...StyleSheet.absoluteFill, borderWidth: SELECTED_BORDER_WIDTH },
  checkbox: {
    position: 'absolute',
    width: CHECKBOX_SIZE,
    height: CHECKBOX_SIZE,
    borderRadius: RADIUS.sm,
    borderWidth: BORDER_WIDTH,
    alignItems: 'center',
    justifyContent: 'center',
  },
});

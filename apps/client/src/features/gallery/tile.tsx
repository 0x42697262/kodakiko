import { useState } from 'react';
import { StyleSheet, Text, View, type GestureResponderEvent } from 'react-native';
import { Touchable } from '../../ui/Touchable';
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
import { localTimeOf, type IndexedEntry } from '@kodakiko/core';
import { UI_SETTINGS_DEFAULTS } from '../../constants/ui-settings-constants';
import type { DeviceSettingsValue } from '../../services/settings/device-settings';
import { Icon } from '../../ui/icons';
import { formatClock } from '../format/format-clock';
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

export type TileDisplay = {
  readonly mode: DeviceSettingsValue['galleryDisplayMode'];
  readonly badges: DeviceSettingsValue['galleryBadges'];
  readonly hideNotePreviews: boolean;
  readonly timeFormat: DeviceSettingsValue['timeFormat'];
};

const DEFAULT_DISPLAY: TileDisplay = {
  mode: UI_SETTINGS_DEFAULTS.galleryDisplayMode,
  badges: UI_SETTINGS_DEFAULTS.galleryBadges,
  hideNotePreviews: UI_SETTINGS_DEFAULTS.hideNotePreviews,
  timeFormat: UI_SETTINGS_DEFAULTS.timeFormat,
};

type TileProps = {
  readonly entryId: string;
  readonly position: number;
  readonly size: number;
  readonly selecting: boolean;
  readonly selected: boolean;
  readonly display?: TileDisplay;
  readonly onPress: (entryId: string, modifiers: TileModifiers) => void;
  readonly onLongPress: (entryId: string) => void;
};

const BADGE_ICON_SIZE = 14;

function Badges({
  entry,
  position,
  badges,
}: {
  readonly entry: IndexedEntry;
  readonly position: number;
  readonly badges: TileDisplay['badges'];
}) {
  const { colors } = useTheme();
  const pill = [styles.pill, { backgroundColor: colors.primary }];
  return (
    <View style={styles.badgeRow} pointerEvents="none">
      {badges.pending && entry.pending ? (
        <View
          testID={listTestId(TEST_IDS.galleryTilePending, position)}
          accessibilityLabel={EN['gallery.notSynced']}
          style={pill}
        >
          <Icon name="upload" size={BADGE_ICON_SIZE} color={colors.onPrimary} />
        </View>
      ) : null}
      {badges.favorite && entry.favorite === true ? (
        <View style={pill}>
          <Icon name="favorite" size={BADGE_ICON_SIZE} color={colors.onPrimary} />
        </View>
      ) : null}
      {badges.video && entry.hasVideo ? (
        <View style={pill}>
          <Icon name="video" size={BADGE_ICON_SIZE} color={colors.onPrimary} />
        </View>
      ) : null}
      {badges.note && entry.notePreview !== '' ? (
        <View style={pill}>
          <Icon name="note" size={BADGE_ICON_SIZE} color={colors.onPrimary} />
        </View>
      ) : null}
      {badges.photoCount && entry.mediaCount > 1 ? (
        <View style={pill}>
          <Text style={[styles.pillText, { color: colors.onPrimary }]}>
            {EN['gallery.moreMedia'](entry.mediaCount - 1)}
          </Text>
        </View>
      ) : null}
    </View>
  );
}

export function Tile({
  entryId,
  position,
  size,
  selecting,
  selected,
  display = DEFAULT_DISPLAY,
  onPress,
  onLongPress,
}: TileProps) {
  const { colors } = useTheme();
  const entry = useIndexedEntry(entryId);
  const [hovered, setHovered] = useState(false);
  if (entry === null) return <View style={{ width: size, height: size }} />;
  const showCheckbox = selecting || hovered;
  const list = display.mode === 'list';
  const thumbSize = list ? LIST_THUMB_SIZE : size;
  const time = formatClock(
    localTimeOf(entry.takenAtUtc, entry.utcOffsetMinutes),
    display.timeFormat,
  );
  const note = display.hideNotePreviews ? '' : entry.notePreview.replace(MARKDOWN_LEAD, '');
  const checkbox = showCheckbox ? (
    <View
      testID={listTestId(GALLERY_IDS.tileCheckbox, position)}
      accessibilityLabel={EN['gallery.selectEntry']}
      style={[
        styles.checkbox,
        styles.topLeft,
        {
          borderColor: colors.onPrimary,
          backgroundColor: selected ? colors.primary : colors.surface,
        },
      ]}
    >
      {selected ? (
        <Text style={{ color: colors.onPrimary }}>{EN['gallery.checkGlyph']}</Text>
      ) : null}
    </View>
  ) : null;
  const image = (
    <View style={{ width: thumbSize, height: thumbSize }}>
      <Thumbnail
        entryId={entryId}
        mediaId={entry.coverMediaId}
        size={thumbSize}
        unreadable={entry.unreadable}
      />
      {selected ? (
        <View
          pointerEvents="none"
          style={[styles.selectedFrame, { borderColor: colors.primary }]}
        />
      ) : null}
      {display.mode === 'photoGrid' || list ? null : (
        <Badges entry={entry} position={position} badges={display.badges} />
      )}
      {display.mode === 'compactGrid' ? (
        <View pointerEvents="none" style={styles.compactCaption}>
          <Text numberOfLines={1} style={styles.compactText}>
            {note === '' ? time : `${time} · ${note}`}
          </Text>
        </View>
      ) : null}
      {checkbox}
    </View>
  );
  return (
    <Touchable
      testID={listTestId(TEST_IDS.galleryTile, position)}
      accessibilityRole="button"
      accessibilityState={{ selected }}
      onPress={(event) => onPress(entryId, tileModifiers(event))}
      onLongPress={() => onLongPress(entryId)}
      onHoverIn={() => setHovered(true)}
      onHoverOut={() => setHovered(false)}
      style={list ? [styles.listRow, { width: size }] : { width: size }}
    >
      {image}
      {list ? (
        <View style={styles.listTexts}>
          <Text
            style={[styles.listTitle, { color: colors.text }]}
          >{`${entry.localDate} ${time}`}</Text>
          {note === '' ? null : (
            <Text numberOfLines={2} style={{ color: colors.textMuted }}>
              {note}
            </Text>
          )}
          {entry.tags.length === 0 ? null : (
            <Text numberOfLines={1} style={[styles.tags, { color: colors.primary }]}>
              {entry.tags.map((tag) => `#${tag}`).join(' ')}
            </Text>
          )}
          <Badges entry={entry} position={position} badges={display.badges} />
        </View>
      ) : null}
      {display.mode === 'comfortableGrid' ? (
        <View style={styles.caption}>
          <Text numberOfLines={1} style={[styles.captionText, { color: colors.text }]}>
            {time}
          </Text>
          {note === '' ? null : (
            <Text numberOfLines={1} style={[styles.captionText, { color: colors.textMuted }]}>
              {note}
            </Text>
          )}
        </View>
      ) : null}
    </Touchable>
  );
}

const LIST_THUMB_SIZE = 72;
const MARKDOWN_LEAD = /^[#>*\-\s]+/;
const CAPTION_SHADE = 'rgba(0,0,0,0.55)';
const CAPTION_TEXT = '#FFFFFF';

const styles = StyleSheet.create({
  badgeRow: {
    position: 'absolute',
    top: BADGE_OFFSET,
    right: BADGE_OFFSET,
    flexDirection: 'row',
    gap: SPACING.xs,
  },
  pill: {
    borderRadius: RADIUS.sm,
    paddingHorizontal: SPACING.xs,
    minHeight: BADGE_ICON_SIZE + SPACING.xs,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pillText: { fontSize: FONT_SIZE.caption, fontWeight: '600' },
  compactCaption: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    paddingHorizontal: SPACING.xs,
    paddingVertical: SPACING.xs,
    backgroundColor: CAPTION_SHADE,
  },
  compactText: { color: CAPTION_TEXT, fontSize: FONT_SIZE.caption },
  caption: { paddingTop: SPACING.xs, paddingBottom: SPACING.sm },
  captionText: { fontSize: FONT_SIZE.caption },
  listRow: { flexDirection: 'row', gap: SPACING.md, alignItems: 'flex-start' },
  listTexts: { flex: 1, gap: SPACING.xs },
  listTitle: { fontSize: FONT_SIZE.body, fontWeight: '600' },
  tags: { fontSize: FONT_SIZE.caption },
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
  dot: {
    position: 'absolute',
    width: PENDING_DOT_SIZE,
    height: PENDING_DOT_SIZE,
    borderRadius: PENDING_DOT_SIZE,
  },
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

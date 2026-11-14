import { type Href } from 'expo-router';
import { useRouter } from '../../navigation/use-router';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { ON_THIS_DAY_MAX_TILES, ON_THIS_DAY_TILE_PX } from '../../constants/gallery-constants';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { entryRoute } from '../../keys/routes';
import { listTestId, testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { useIndexedEntry, useOnThisDay } from './hooks';
import { Thumbnail } from './thumbnail';

function OnThisDayTile({ entryId, testID }: { readonly entryId: string; readonly testID: string }) {
  const router = useRouter();
  const entry = useIndexedEntry(entryId);
  if (entry === null) return null;
  return (
    <Pressable testID={testID} accessibilityRole="button" onPress={() => router.push(entryRoute(entryId) as Href)}>
      <Thumbnail
        entryId={entryId}
        mediaId={entry.coverMediaId}
        size={ON_THIS_DAY_TILE_PX}
        unreadable={entry.unreadable}
      />
    </Pressable>
  );
}

export function OnThisDayCard() {
  const { colors } = useTheme();
  const groups = useOnThisDay();
  if (groups.length === 0) return null;
  let tilePosition = 0;
  return (
    <View testID={GALLERY_IDS.onThisDayCard} style={[styles.card, { backgroundColor: colors.surface }]}>
      <Text testID={testId('onThisDay.title')} style={[styles.title, { color: colors.text }]}>
        {EN['onThisDay.title']}
      </Text>
      <ScrollView horizontal contentContainerStyle={styles.groups}>
        {groups.map((group, groupPosition) => (
          <View key={group.yearsAgo} testID={listTestId(GALLERY_IDS.onThisDayGroup, groupPosition)} style={styles.group}>
            <Text style={[styles.label, { color: colors.textMuted }]}>
              {EN['onThisDay.yearsAgo'](group.yearsAgo)}
            </Text>
            <View style={styles.tiles}>
              {group.entryIds.slice(0, ON_THIS_DAY_MAX_TILES).map((entryId) => {
                const testID = listTestId(GALLERY_IDS.onThisDayTile, tilePosition);
                tilePosition += 1;
                return <OnThisDayTile key={entryId} entryId={entryId} testID={testID} />;
              })}
            </View>
          </View>
        ))}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  card: { padding: SPACING.md, gap: SPACING.sm, marginBottom: SPACING.md },
  title: { fontSize: FONT_SIZE.headline, fontWeight: '600' },
  groups: { gap: SPACING.lg },
  group: { gap: SPACING.xs },
  label: { fontSize: FONT_SIZE.caption },
  tiles: { flexDirection: 'row', gap: SPACING.xs },
});

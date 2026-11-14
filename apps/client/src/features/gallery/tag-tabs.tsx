import { ScrollView, StyleSheet, Text, Pressable, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { useTheme } from '../../ui/theme';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useGalleryFilter, useIndexVersion } from './hooks';
import { useMemo, useSyncExternalStore } from 'react';

const INDICATOR_HEIGHT = 3;
const FAVORITES = '\u0000favorites';

export function TagTabs() {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const filter = useGalleryFilter();
  const version = useIndexVersion();
  const tags = useMemo(() => services.index.index()?.allTags() ?? [], [services.index, version]);
  const index = services.index.index();
  const base = index?.query({ ...filter, tags: [], attributes: undefined }) ?? [];
  const total = base.reduce((sum, section) => sum + section.entryIds.length, 0);
  const favorites = base.reduce((sum, section) => sum + section.entryIds.filter((id) => index?.get(id)?.favorite === true).length, 0);
  if (!settings.galleryShowTagTabs || (tags.length === 0 && favorites === 0)) return null;
  const favoriteOnly = settings.galleryFilters.favorite === 'include';
  const selected = filter.tags.length === 1 ? filter.tags[0] : filter.tags.length === 0 ? (favoriteOnly ? FAVORITES : null) : undefined;
  const choose = (tag: string | null) => {
    services.galleryFilter.set({ ...services.galleryFilter.get(), tags: tag === null || tag === FAVORITES ? [] : [tag] });
    const favorite = tag === FAVORITES ? 'include' : 'off';
    if (settings.galleryFilters.favorite !== favorite) {
      services.deviceSettings.update({ galleryFilters: { ...settings.galleryFilters, favorite } }).catch(() => undefined);
    }
  };
  const tabs = [
    { key: 'all', tag: null as string | null, label: EN['gallery.tabAll'], count: total, testID: 'gallery.tagTab.all' },
    ...(favorites === 0 ? [] : [{ key: FAVORITES, tag: FAVORITES as string | null, label: EN['gallery.tabFavorites'], count: favorites, testID: 'gallery.tagTab.favorites' }]),
    ...tags.map((item, position) => ({ key: item.tag, tag: item.tag as string | null, label: item.tag, count: item.count, testID: `gallery.tagTab.${position}` })),
  ];
  return (
    <View style={[styles.bar, { borderBottomColor: colors.border }]}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
        {tabs.map((tab) => {
          const active = tab.tag === selected;
          return (
            <Pressable
              key={tab.key}
              testID={tab.testID}
              role="tab"
              aria-selected={active}
              onPress={() => choose(tab.tag)}
              style={styles.tab}
            >
              <Text style={[styles.label, { color: active ? colors.primary : colors.textMuted }]}>
                {settings.galleryShowCounts ? EN['gallery.tabCount'](tab.label, tab.count) : tab.label}
              </Text>
              <View style={[styles.indicator, { backgroundColor: active ? colors.primary : 'transparent' }]} />
            </Pressable>
          );
        })}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  bar: { borderBottomWidth: StyleSheet.hairlineWidth },
  row: { paddingHorizontal: SPACING.sm },
  tab: { paddingHorizontal: SPACING.md, paddingTop: SPACING.md, alignItems: 'center', gap: SPACING.sm },
  label: { fontSize: FONT_SIZE.body, fontWeight: '600' },
  indicator: { height: INDICATOR_HEIGHT, alignSelf: 'stretch', borderTopLeftRadius: INDICATOR_HEIGHT, borderTopRightRadius: INDICATOR_HEIGHT },
});

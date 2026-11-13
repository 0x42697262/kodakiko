import { ScrollView, StyleSheet, Text, Pressable, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { useTheme } from '../../ui/theme';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useGalleryFilter, useIndexVersion } from './hooks';
import { useMemo, useSyncExternalStore } from 'react';

const INDICATOR_HEIGHT = 3;

export function TagTabs() {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const filter = useGalleryFilter();
  const version = useIndexVersion();
  const tags = useMemo(() => services.index.index()?.allTags() ?? [], [services.index, version]);
  if (!settings.galleryShowTagTabs || tags.length === 0) return null;
  const selected = filter.tags.length === 1 ? filter.tags[0] : filter.tags.length === 0 ? null : undefined;
  const total = (services.index.index()?.query({ ...filter, tags: [] }) ?? []).reduce((sum, section) => sum + section.entryIds.length, 0);
  const tabs = [{ key: 'all', tag: null as string | null, label: EN['gallery.tabAll'], count: total }, ...tags.map((item) => ({ key: item.tag, tag: item.tag as string | null, label: item.tag, count: item.count }))];
  return (
    <View style={[styles.bar, { borderBottomColor: colors.border }]}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
        {tabs.map((tab, position) => {
          const active = tab.tag === selected;
          return (
            <Pressable
              key={tab.key}
              testID={tab.tag === null ? 'gallery.tagTab.all' : `gallery.tagTab.${position - 1}`}
              role="tab"
              aria-selected={active}
              onPress={() => services.galleryFilter.set({ ...services.galleryFilter.get(), tags: tab.tag === null ? [] : [tab.tag] })}
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

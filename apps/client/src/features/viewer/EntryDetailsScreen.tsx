import { useEffect, useState, useSyncExternalStore } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import type { ObjectRow } from '../../adapters/rowstore/rowstore';
import { EN } from '../../text/en';
import { useTheme } from '../../ui/theme';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { SettingsPage } from '../settings/settings-ui';
import { entryDetailSections, type DetailSection } from './entry-details-model';

export function EntryDetailsScreen({ entryId }: { readonly entryId: string }) {
  const { services, rowStore } = useAppContext();
  const { colors } = useTheme();
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const [sections, setSections] = useState<readonly DetailSection[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = async (): Promise<void> => {
      const metadata = await services.entries.readMetadata(entryId);
      const { row, objects } = await rowStore.transaction(async (tx) => {
        const entry = await tx.entries.get(entryId);
        const byMedia = new Map<string, readonly ObjectRow[]>();
        for (const item of metadata.media) byMedia.set(item.mediaId, await tx.objects.byMedia(item.mediaId));
        return { row: entry, objects: byMedia };
      });
      if (!cancelled) setSections(entryDetailSections({ entryId, metadata, row, objects, technical: settings.showTechnicalDetails }));
    };
    load().catch(() => setSections([]));
    return () => {
      cancelled = true;
    };
  }, [entryId, services.entries, rowStore, settings.showTechnicalDetails]);

  return (
    <SettingsPage testID="details.screen" title={EN['details.title']}>
      {(sections ?? []).map((section) => (
        <View key={section.title} style={styles.section}>
          <Text style={[styles.header, { color: colors.primary }]}>{section.title}</Text>
          {section.rows.map((row) => (
            <View key={row.label} style={styles.row}>
              <Text style={[styles.label, { color: colors.textMuted }]}>{row.label}</Text>
              <Text selectable style={[styles.value, { color: colors.text }]}>
                {row.value}
              </Text>
            </View>
          ))}
        </View>
      ))}
    </SettingsPage>
  );
}

const styles = StyleSheet.create({
  section: { gap: SPACING.sm, paddingBottom: SPACING.lg },
  header: { fontSize: FONT_SIZE.caption, fontWeight: '600' },
  row: { gap: SPACING.xs },
  label: { fontSize: FONT_SIZE.caption },
  value: { fontSize: FONT_SIZE.body },
});

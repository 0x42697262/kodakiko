import { useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import type { CacheUsage } from '../../services/originals/originals-service';
import { EN } from '../../text/en';
import { useTheme } from '../../ui/theme';
import { FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { formatBytes } from '../format/format-bytes';
import { EMPTY_GALLERY_FILTER } from '../gallery/filter-model';
import { todayLocalDate, useIndexVersion } from '../gallery/hooks';
import { LabeledValue, SettingsPage } from '../settings/settings-ui';
import { journalStatistics } from './statistics-model';

const BAR_HEIGHT = 120;
const DATE_TIME_CHARS = 16;
const BAR_LABEL_CHARS = 2;
const MONTH_LABEL_START = 5;

function Bars({ testID, bars }: { readonly testID: string; readonly bars: readonly { readonly label: string; readonly count: number }[] }) {
  const { colors } = useTheme();
  const max = Math.max(1, ...bars.map((bar) => bar.count));
  return (
    <View testID={testID} style={styles.bars}>
      {bars.map((bar) => (
        <View key={bar.label} style={styles.barColumn} accessibilityLabel={`${bar.label}: ${bar.count}`}>
          <Text style={[styles.barCount, { color: colors.textMuted }]}>{bar.count === 0 ? '' : bar.count}</Text>
          <View style={[styles.bar, { height: (bar.count / max) * BAR_HEIGHT, backgroundColor: colors.primary }]} />
          <Text style={[styles.barLabel, { color: colors.textMuted }]}>{bar.label}</Text>
        </View>
      ))}
    </View>
  );
}

export function StatisticsScreen() {
  const { services, clock } = useAppContext();
  const { colors } = useTheme();
  const version = useIndexVersion();
  const sync = useSyncExternalStore(services.sync.subscribe, services.sync.status);
  const user = useSyncExternalStore(services.account.user.subscribe, services.account.user.get);
  const [usage, setUsage] = useState<CacheUsage | null>(null);
  useEffect(() => {
    services.originals.usage().then(setUsage).catch(() => undefined);
  }, [services.originals]);
  const stats = useMemo(() => {
    const index = services.index.index();
    const entries = (index?.query(EMPTY_GALLERY_FILTER) ?? []).flatMap((section) => section.entryIds).map((id) => index?.get(id)).filter((entry) => entry !== null && entry !== undefined);
    return journalStatistics(entries, todayLocalDate(clock.now()));
  }, [services.index, version, clock]);
  const none = EN['stats.none'];
  const header = (title: string) => <Text style={[styles.header, { color: colors.primary }]}>{title}</Text>;
  return (
    <SettingsPage testID="stats.screen" title={EN['stats.title']}>
      {header(EN['stats.journal'])}
      <LabeledValue testID="stats.entries" label={EN['stats.entries']} value={String(stats.entries)} />
      <LabeledValue label={EN['stats.items']} value={String(stats.mediaItems)} />
      <LabeledValue label={EN['stats.withVideo']} value={String(stats.entriesWithVideo)} />
      <LabeledValue label={EN['stats.withNote']} value={String(stats.entriesWithNote)} />
      <LabeledValue label={EN['stats.firstLast']} value={stats.firstDay === null ? none : `${stats.firstDay} \u2013 ${stats.lastDay ?? none}`} />
      {header(EN['stats.perMonth'])}
      <Bars testID="stats.perMonth" bars={stats.perMonth.map((item) => ({ label: item.month.slice(MONTH_LABEL_START, MONTH_LABEL_START + BAR_LABEL_CHARS), count: item.count }))} />
      {header(EN['stats.perYear'])}
      <Bars testID="stats.perYear" bars={stats.perYear.map((item) => ({ label: item.year, count: item.count }))} />
      {header(EN['stats.topTags'])}
      {stats.topTags.length === 0 ? <Text style={{ color: colors.textMuted }}>{none}</Text> : null}
      {stats.topTags.map((item) => (
        <LabeledValue key={item.tag} label={`#${item.tag}`} value={String(item.count)} />
      ))}
      {header(EN['stats.storage'])}
      <LabeledValue label={EN['stats.onDevice']} value={usage === null ? none : formatBytes(usage.originalsBytes)} />
      <LabeledValue label={EN['stats.thumbnails']} value={usage === null ? none : formatBytes(usage.thumbnailsBytes)} />
      <LabeledValue label={EN['stats.waiting']} value={usage === null ? none : formatBytes(usage.pendingUploadBytes)} />
      <LabeledValue label={EN['stats.server']} value={user === null ? none : formatBytes(user.usedBytes)} />
      {header(EN['stats.sync'])}
      <LabeledValue label={EN['stats.lastSync']} value={sync.lastSuccessAt === null ? none : new Date(sync.lastSuccessAt).toISOString().slice(0, DATE_TIME_CHARS).replace('T', ' ')} />
      <LabeledValue label={EN['stats.queued']} value={String(sync.queued)} />
      <LabeledValue label={EN['stats.failed']} value={String(sync.dead)} />
    </SettingsPage>
  );
}

const styles = StyleSheet.create({
  header: { fontSize: FONT_SIZE.caption, fontWeight: '600', paddingTop: SPACING.md },
  bars: { flexDirection: 'row', alignItems: 'flex-end', gap: SPACING.xs, minHeight: BAR_HEIGHT + SPACING.xxl },
  barColumn: { flex: 1, alignItems: 'center', justifyContent: 'flex-end', gap: SPACING.xs },
  bar: { width: '100%', borderTopLeftRadius: RADIUS.sm, borderTopRightRadius: RADIUS.sm },
  barCount: { fontSize: FONT_SIZE.caption },
  barLabel: { fontSize: FONT_SIZE.caption },
});

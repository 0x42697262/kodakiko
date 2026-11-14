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
import { Icon, type IconKey } from '../../ui/icons';
import { journalStatistics } from './statistics-model';

const BAR_HEIGHT = 120;
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
  const quota = user === null ? null : (user.quotaBytes ?? services.config.get()?.defaultQuotaBytes ?? null);
  const totalMinutes = Math.round(stats.videoMs / MS_PER_MINUTE);
  return (
    <SettingsPage testID="stats.screen" title={EN['stats.title']}>
      <StatCard
        testID="stats.overview"
        title={EN['stats.overview']}
        metrics={[
          { value: String(stats.entries), label: EN['stats.entries'], icon: 'gallery' },
          { value: String(stats.mediaItems), label: EN['stats.media'], icon: 'camera' },
          { value: formatBytes(stats.originalBytes), label: EN['stats.size'], icon: 'storage' },
        ]}
      />
      <StatCard
        testID="stats.mediaCard"
        title={EN['stats.media']}
        metrics={[
          { value: String(stats.photos), label: EN['stats.photos'], icon: 'camera' },
          { value: String(stats.videos), label: EN['stats.videos'], icon: 'video' },
          {
            value: totalMinutes === 0 ? EN['stats.seconds'](Math.round(stats.videoMs / MS_PER_SECOND)) : EN['stats.hours'](Math.floor(totalMinutes / MINUTES_PER_HOUR), totalMinutes % MINUTES_PER_HOUR),
            label: EN['stats.videoLength'],
            icon: 'play',
          },
        ]}
      />
      <StatCard
        testID="stats.journalCard"
        title={EN['stats.journal']}
        metrics={[
          { value: String(stats.entriesWithNote), label: EN['stats.notes'], icon: 'note' },
          { value: String(stats.tagCount), label: EN['stats.tagsCount'], icon: 'tags' },
          { value: String(stats.favorites), label: EN['stats.favorites'], icon: 'favorite' },
        ]}
      />
      <StatCard
        testID="stats.deviceCard"
        title={EN['stats.storage']}
        metrics={[
          { value: usage === null ? none : formatBytes(usage.originalsBytes), label: EN['stats.onDevice'], icon: 'storage' },
          { value: usage === null ? none : formatBytes(usage.thumbnailsBytes), label: EN['stats.thumbnails'], icon: 'gallery' },
          { value: usage === null ? none : formatBytes(usage.pendingUploadBytes), label: EN['stats.waiting'], icon: 'upload' },
        ]}
      />
      {user === null ? null : (
        <StatCard
          testID="stats.serverCard"
          title={EN['stats.server']}
          metrics={[
            { value: formatBytes(user.usedBytes), label: EN['stats.used'], icon: 'upload' },
            { value: quota === null ? none : formatBytes(quota), label: EN['stats.quota'], icon: 'storage' },
            { value: quota === null ? none : formatBytes(Math.max(0, quota - user.usedBytes)), label: EN['stats.free'], icon: 'info' },
          ]}
          ratio={quota === null || quota === 0 ? null : user.usedBytes / quota}
        />
      )}
      <StatCard
        testID="stats.syncCard"
        title={EN['stats.sync']}
        metrics={[
          { value: sync.lastSuccessAt === null ? none : new Date(sync.lastSuccessAt).toISOString().slice(TIME_START, TIME_END), label: EN['stats.lastSync'], icon: 'sync' },
          { value: String(sync.queued), label: EN['stats.queued'], icon: 'upload' },
          { value: String(sync.dead), label: EN['stats.failed'], icon: 'info' },
        ]}
      />
      {header(EN['stats.perMonth'])}
      <Bars testID="stats.perMonth" bars={stats.perMonth.map((item) => ({ label: item.month.slice(MONTH_LABEL_START, MONTH_LABEL_START + BAR_LABEL_CHARS), count: item.count }))} />
      {header(EN['stats.perYear'])}
      <Bars testID="stats.perYear" bars={stats.perYear.map((item) => ({ label: item.year, count: item.count }))} />
      {header(EN['stats.topTags'])}
      {stats.topTags.length === 0 ? <Text style={{ color: colors.textMuted }}>{none}</Text> : null}
      {stats.topTags.map((item) => (
        <LabeledValue key={item.tag} label={`#${item.tag}`} value={String(item.count)} />
      ))}
      <LabeledValue label={EN['stats.firstLast']} value={stats.firstDay === null ? none : `${stats.firstDay} \u2013 ${stats.lastDay ?? none}`} />
    </SettingsPage>
  );
}

type Metric = { readonly value: string; readonly label: string; readonly icon: IconKey };

function StatCard({ testID, title, metrics, ratio = null }: { readonly testID: string; readonly title: string; readonly metrics: readonly Metric[]; readonly ratio?: number | null }) {
  const { colors } = useTheme();
  return (
    <View testID={testID} style={styles.cardBlock}>
      <Text style={[styles.cardTitle, { color: colors.text }]}>{title}</Text>
      <View style={[styles.card, { backgroundColor: colors.surface }]}>
        <View style={styles.metrics}>
          {metrics.map((metric) => (
            <View key={metric.label} style={styles.metric}>
              <Text style={[styles.metricValue, { color: colors.text }]}>{metric.value}</Text>
              <Text style={[styles.metricLabel, { color: colors.textMuted }]}>{metric.label}</Text>
              <Icon name={metric.icon} size={METRIC_ICON_SIZE} color={colors.primary} />
            </View>
          ))}
        </View>
        {ratio === null ? null : (
          <View style={[styles.quotaTrack, { backgroundColor: colors.border }]}>
            <View style={[styles.quotaFill, { width: `${Math.min(1, ratio) * PERCENT}%`, backgroundColor: colors.primary }]} />
          </View>
        )}
      </View>
    </View>
  );
}

const MS_PER_MINUTE = 60_000;
const MS_PER_SECOND = 1_000;
const MINUTES_PER_HOUR = 60;
const TIME_START = 5;
const TIME_END = 16;
const METRIC_ICON_SIZE = 22;
const PERCENT = 100;
const QUOTA_BAR_HEIGHT = 6;

const styles = StyleSheet.create({
  cardBlock: { gap: SPACING.sm },
  cardTitle: { fontSize: FONT_SIZE.body, fontWeight: '600', paddingTop: SPACING.sm },
  card: { borderRadius: RADIUS.md + SPACING.sm, padding: SPACING.lg, gap: SPACING.md },
  metrics: { flexDirection: 'row' },
  metric: { flex: 1, alignItems: 'center', gap: SPACING.xs },
  metricValue: { fontSize: FONT_SIZE.title, fontWeight: '700' },
  metricLabel: { fontSize: FONT_SIZE.caption, textAlign: 'center' },
  quotaTrack: { height: QUOTA_BAR_HEIGHT, borderRadius: QUOTA_BAR_HEIGHT, overflow: 'hidden' },
  quotaFill: { height: QUOTA_BAR_HEIGHT },
  header: { fontSize: FONT_SIZE.caption, fontWeight: '600', paddingTop: SPACING.md },
  bars: { flexDirection: 'row', alignItems: 'flex-end', gap: SPACING.xs, minHeight: BAR_HEIGHT + SPACING.xxl },
  barColumn: { flex: 1, alignItems: 'center', justifyContent: 'flex-end', gap: SPACING.xs },
  bar: { width: '100%', borderTopLeftRadius: RADIUS.sm, borderTopRightRadius: RADIUS.sm },
  barCount: { fontSize: FONT_SIZE.caption },
  barLabel: { fontSize: FONT_SIZE.caption },
});

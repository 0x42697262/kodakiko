import { COLLECTION_NAME_MAX_CHARS, LINK_EXPIRIES, type EntryMetadataV1, type LinkExpiry } from '@kodakiko/contract';
import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import type { z } from 'zod';
import { NetworkError } from '../../adapters/http/http';
import { useAppContext } from '../../composition/app-context';
import { listTestId } from '../../keys/test-ids';
import type { CreateLinkProgress, CreateLinkResult } from '../../services/links/public-links-service';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Body, Title } from '../../ui/Typography';
import { BORDER_WIDTH, FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { useThumbnail } from '../gallery/thumbnail';
import { copyText, shareText } from './clipboard';
import { formatSize } from './format-size';
import { LINK_IDS } from './links-ids';

type Metadata = z.infer<typeof EntryMetadataV1>;
type Loaded = { readonly entryId: string; readonly metadata: Metadata };

const MEDIA_TILE = 88;
const UNSELECTED_OPACITY = 0.4;
const TILE_BORDER = 2;
const DATE_CHARS = 10;
const DEFAULT_EXPIRY: LinkExpiry = '7d';
const EXPIRY_LABELS: Readonly<Record<LinkExpiry, string>> = {
  '1h': EN['links.expiry1h'],
  '1d': EN['links.expiry1d'],
  '7d': EN['links.expiry7d'],
  '14d': EN['links.expiry14d'],
  '30d': EN['links.expiry30d'],
  never: EN['links.expiryNever'],
  one_view: EN['links.expiryOneView'],
};

function MediaToggle({ entryId, mediaId, position, selected, onToggle }: {
  readonly entryId: string;
  readonly mediaId: string;
  readonly position: number;
  readonly selected: boolean;
  readonly onToggle: () => void;
}) {
  const { colors } = useTheme();
  const uri = useThumbnail(entryId, mediaId);
  return (
    <Pressable
      testID={listTestId(LINK_IDS.mediaToggle, position)}
      accessibilityRole="checkbox"
      accessibilityState={{ checked: selected }}
      onPress={onToggle}
      style={[styles.tile, { borderColor: selected ? colors.primary : colors.border, opacity: selected ? 1 : UNSELECTED_OPACITY }]}
    >
      {uri === null ? null : <Image source={{ uri }} style={styles.image} />}
      <Text style={[styles.check, { backgroundColor: selected ? colors.primary : colors.surface, color: colors.onPrimary }]}>
        {selected ? '\u2713' : ''}
      </Text>
    </Pressable>
  );
}

function progressText(progress: CreateLinkProgress): string {
  if (progress.step === 'preparing') return EN['links.preparing'](progress.done + 1, progress.total);
  if (progress.step === 'uploading') return EN['links.uploading'](formatSize(progress.bytesDone), formatSize(progress.bytesTotal));
  return EN['links.registering'];
}

export function LinkCreateScreen({ entryIds }: { readonly entryIds: readonly string[] }) {
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const [entries, setEntries] = useState<readonly Loaded[]>([]);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [title, setTitle] = useState<string>(EN['links.defaultTitle']);
  const [includeNotes, setIncludeNotes] = useState(true);
  const [expiry, setExpiry] = useState<LinkExpiry>(DEFAULT_EXPIRY);
  const [progress, setProgress] = useState<CreateLinkProgress | null>(null);
  const [result, setResult] = useState<CreateLinkResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const abort = useRef<AbortController | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const loaded: Loaded[] = [];
      for (const entryId of entryIds) loaded.push({ entryId, metadata: await services.entries.readMetadata(entryId) });
      if (cancelled) return;
      setEntries(loaded);
      setSelected(new Set(loaded.flatMap((entry) => entry.metadata.media.map((media) => media.mediaId))));
    })().catch(() => undefined);
    return () => {
      cancelled = true;
      abort.current?.abort();
    };
  }, [entryIds, services.entries]);

  const estimate = useMemo(
    () =>
      entries
        .flatMap((entry) => entry.metadata.media)
        .filter((media) => selected.has(media.mediaId))
        .reduce((sum, media) => sum + media.originalPlaintextBytes + media.thumbnail.plaintextBytes, 0),
    [entries, selected],
  );

  function setMany(mediaIds: readonly string[], on: boolean): void {
    const next = new Set(selected);
    for (const mediaId of mediaIds) {
      if (on) next.add(mediaId);
      else next.delete(mediaId);
    }
    setSelected(next);
  }

  async function create(): Promise<void> {
    const controller = new AbortController();
    abort.current = controller;
    setError(null);
    setProgress({ step: 'preparing', done: 0, total: entries.length, bytesDone: 0, bytesTotal: 0 });
    try {
      setResult(
        await services.links.create(
          { entryIds: entries.map((entry) => entry.entryId), mediaIds: selected, title, expiry, includeNotes },
          setProgress,
          controller.signal,
        ),
      );
    } catch (caught) {
      setError(caught instanceof NetworkError ? EN['links.offline'] : EN['links.failed']);
    } finally {
      setProgress(null);
    }
  }

  if (result !== null) {
    const skipped = [
      result.skippedEntries > 0 ? EN['links.skippedEntries'](result.skippedEntries) : null,
      result.skippedUndecodable > 0 ? EN['links.skippedUndecodable'](result.skippedUndecodable) : null,
      result.skippedUnsupportedVideo > 0 ? EN['links.skippedVideo'](result.skippedUnsupportedVideo) : null,
    ].filter((line): line is string => line !== null);
    return (
      <Screen testID={LINK_IDS.createScreen} scroll>
        <Title text={EN['links.ready']} />
        <Text testID={LINK_IDS.url} selectable style={[styles.url, { color: colors.text, borderColor: colors.border }]}>
          {result.url}
        </Text>
        {skipped.map((line) => (
          <Body key={line} text={line} />
        ))}
        {copied ? <Banner testID="links.copied" kind="info" message={EN['links.copied']} /> : null}
        <View style={styles.row}>
          {Platform.OS === 'web' ? (
            <Button
              testID={LINK_IDS.copy}
              label={EN['links.copy']}
              onPress={() => {
                copyText(result.url)
                  .then(setCopied)
                  .catch(() => undefined);
              }}
            />
          ) : (
            <Button testID={LINK_IDS.share} label={EN['links.share']} onPress={() => shareText(result.url).catch(() => undefined)} />
          )}
          <Button testID={LINK_IDS.done} label={EN['links.done']} variant="secondary" onPress={() => router.back()} />
        </View>
      </Screen>
    );
  }

  let position = 0;
  return (
    <Screen testID={LINK_IDS.createScreen} scroll>
      <Title text={EN['links.newTitle']} />
      <TextField testID={LINK_IDS.title} label={EN['links.titleField']} value={title} onChangeText={setTitle} maxLength={COLLECTION_NAME_MAX_CHARS} />
      <View style={styles.row}>
        <Button testID={LINK_IDS.selectAll} label={EN['links.selectAll']} variant="secondary" onPress={() => setMany(entries.flatMap((entry) => entry.metadata.media.map((media) => media.mediaId)), true)} />
        <Button testID={LINK_IDS.selectNone} label={EN['links.selectNone']} variant="secondary" onPress={() => setSelected(new Set())} />
      </View>
      {entries.map((entry, entryPosition) => (
        <View key={entry.entryId} style={styles.section}>
          <Text style={[styles.heading, { color: colors.text }]} numberOfLines={1}>
            {entry.metadata.takenAtUtc.slice(0, DATE_CHARS)} {entry.metadata.note.split('\n')[0]}
          </Text>
          <View style={styles.row}>
            <Button testID={listTestId(LINK_IDS.entryAll, entryPosition)} label={EN['links.selectAll']} variant="secondary" onPress={() => setMany(entry.metadata.media.map((media) => media.mediaId), true)} />
            <Button testID={listTestId(LINK_IDS.entryNone, entryPosition)} label={EN['links.selectNone']} variant="secondary" onPress={() => setMany(entry.metadata.media.map((media) => media.mediaId), false)} />
          </View>
          <View style={styles.grid}>
            {entry.metadata.media.map((media) => {
              const index = position;
              position += 1;
              return (
                <MediaToggle
                  key={media.mediaId}
                  entryId={entry.entryId}
                  mediaId={media.mediaId}
                  position={index}
                  selected={selected.has(media.mediaId)}
                  onToggle={() => setMany([media.mediaId], !selected.has(media.mediaId))}
                />
              );
            })}
          </View>
        </View>
      ))}
      <Pressable
        testID={LINK_IDS.includeNotes}
        accessibilityRole="switch"
        accessibilityState={{ checked: includeNotes }}
        onPress={() => setIncludeNotes(!includeNotes)}
        style={styles.switchRow}
      >
        <Text style={{ color: colors.text }}>{EN['links.includeNotes']}</Text>
        <Text style={[styles.switch, { backgroundColor: includeNotes ? colors.primary : colors.border, color: colors.onPrimary }]}>
          {includeNotes ? '\u25CF' : '\u25CB'}
        </Text>
      </Pressable>
      <Text style={{ color: colors.textMuted }}>{EN['links.expiry']}</Text>
      <View style={styles.row}>
        {LINK_EXPIRIES.map((value) => (
          <Button
            key={value}
            testID={`${LINK_IDS.expiry}.${value}`}
            label={EXPIRY_LABELS[value]}
            variant={value === expiry ? 'primary' : 'secondary'}
            onPress={() => setExpiry(value)}
          />
        ))}
      </View>
      <Body text={EN['links.estimate'](formatSize(estimate))} />
      <Banner testID="links.warning" kind="warning" message={EN['links.warning']} />
      {error === null ? null : <Banner testID={LINK_IDS.error} kind="error" message={error} />}
      {progress === null ? null : <Body testID={LINK_IDS.progress} text={progressText(progress)} />}
      <View style={styles.row}>
        <Button testID="links.cancel" label={EN['links.cancel']} variant="secondary" onPress={() => router.back()} />
        <Button
          testID={LINK_IDS.create}
          label={EN['links.create']}
          busy={progress !== null}
          disabled={selected.size === 0 || title.trim().length === 0}
          onPress={() => create().catch(() => undefined)}
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  section: { gap: SPACING.sm },
  heading: { fontSize: FONT_SIZE.body, fontWeight: '600' },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  tile: { width: MEDIA_TILE, height: MEDIA_TILE, borderWidth: TILE_BORDER, borderRadius: RADIUS.sm, overflow: 'hidden' },
  image: { width: '100%', height: '100%' },
  check: { position: 'absolute', top: SPACING.xs, right: SPACING.xs, width: SPACING.lg, height: SPACING.lg, borderRadius: RADIUS.sm, textAlign: 'center', fontSize: FONT_SIZE.caption },
  switchRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: SPACING.sm },
  switch: { paddingHorizontal: SPACING.md, paddingVertical: SPACING.xs, borderRadius: RADIUS.md },
  url: { padding: SPACING.md, borderWidth: BORDER_WIDTH, borderRadius: RADIUS.sm, fontSize: FONT_SIZE.body },
});

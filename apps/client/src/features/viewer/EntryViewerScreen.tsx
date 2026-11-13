import { deviceOffsetMinutes, type IndexedEntry } from '@kodakiko/core';
import type { EntryMetadataV1 } from '@kodakiko/contract';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
  type LayoutChangeEvent,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
} from 'react-native';
import type { z } from 'zod';
import { useAppContext } from '../../composition/app-context';
import { APP_ROUTES, entryRoute } from '../../keys/routes';
import { TEST_IDS, listTestId, testId } from '../../keys/test-ids';
import type { OriginalRef } from '../../services/originals/original-source';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { Body } from '../../ui/Typography';
import { FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { formatEntryTime } from '../entries/format-entry-time';
import { LocationRow } from '../entries/location-row';
import { EMPTY_GALLERY_FILTER } from '../gallery/filter-model';
import { useIndexStatus, useIndexVersion, useIndexedEntry } from '../gallery/hooks';
import { Sheet } from '../gallery/sheet';
import { useThumbnail } from '../gallery/thumbnail';
import { MarkdownView } from '../markdown/markdown-view';
import { ZoomablePhoto } from '../media/zoomable-photo';
import { ORIGINAL_WINDOW, PAGE_ASPECT, PERCENT, PAGE_MAX_SCREEN_SHARE, VIEWER_IDS } from './constants';
import { useViewerKeys } from './use-viewer-keys';
import { ShareEntriesSheet } from '../sharing/ShareEntriesSheet';

type Metadata = z.infer<typeof EntryMetadataV1>;
type Descriptor = Metadata['media'][number];
type Overlay = 'none' | 'more' | 'confirmDelete' | 'share';

type PageProps = {
  readonly entryId: string;
  readonly media: Descriptor;
  readonly position: number;
  readonly width: number;
  readonly height: number;
  readonly active: boolean;
  readonly near: boolean;
  readonly onZoomChange: (zoomed: boolean) => void;
};

function useOriginalDownload(mediaId: string, enabled: boolean): { readonly received: number; readonly total: number } | null {
  const { services } = useAppContext();
  const [progress, setProgress] = useState<{ readonly received: number; readonly total: number } | null>(null);
  useEffect(() => {
    if (!enabled) return undefined;
    let unsubscribe: (() => void) | null = null;
    let cancelled = false;
    services.originals
      .originalObjectId(mediaId)
      .then((objectId) => {
        if (cancelled || objectId === null) return;
        unsubscribe = services.downloader.onProgress(objectId, (received, total) => setProgress({ received, total }));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      unsubscribe?.();
      setProgress(null);
    };
  }, [mediaId, enabled, services.originals, services.downloader]);
  return progress;
}

function MediaPage({ entryId, media, position, width, height, active, near, onZoomChange }: PageProps) {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const thumbnail = useThumbnail(entryId, media.mediaId);
  const [original, setOriginal] = useState<OriginalRef | 'missing' | 'failed' | null>(null);
  const [attempt, setAttempt] = useState(0);
  const progress = useOriginalDownload(media.mediaId, near && media.kind === 'photo');

  useEffect(() => {
    if (!near || media.kind !== 'photo') return undefined;
    let cancelled = false;
    let opened: OriginalRef | null = null;
    services.originals
      .open(entryId, media.mediaId)
      .then((ref) => {
        if (ref === 'missing') {
          if (!cancelled) setOriginal('missing');
          return;
        }
        opened = ref;
        if (cancelled) ref.release().catch(() => undefined);
        else setOriginal(ref);
      })
      .catch(() => {
        if (!cancelled) setOriginal('failed');
      });
    return () => {
      cancelled = true;
      setOriginal(null);
      opened?.release().catch(() => undefined);
    };
  }, [near, entryId, media.mediaId, media.kind, services.originals, attempt]);

  const page = { width, height };
  if (media.kind === 'video') {
    const playable = services.videoLauncher.canPlay();
    return (
      <View testID={listTestId(TEST_IDS.viewerPage, position)} style={[styles.page, page, { backgroundColor: colors.surface }]}>
        {thumbnail === null ? null : (
          <ZoomablePhoto
            uri={thumbnail}
            position={position}
            viewportWidth={width}
            viewportHeight={height}
            imageWidth={media.thumbnail.width}
            imageHeight={media.thumbnail.height}
            active={false}
            onZoomChange={() => undefined}
          />
        )}
        <View style={styles.playOverlay}>
          <Button
            testID={testId('viewer.play')}
            label={EN['viewer.play']}
            disabled={!playable}
            onPress={() => services.videoLauncher.open(entryId, media.mediaId)}
          />
        </View>
      </View>
    );
  }
  const loaded = original !== null && typeof original === 'object';
  const uri = loaded ? original.uri : thumbnail;
  return (
    <View testID={listTestId(TEST_IDS.viewerPage, position)} style={[styles.page, page, { backgroundColor: colors.surface }]}>
      {uri === null ? null : (
        <ZoomablePhoto
          uri={uri}
          position={position}
          viewportWidth={width}
          viewportHeight={height}
          imageWidth={media.width}
          imageHeight={media.height}
          active={active}
          onZoomChange={onZoomChange}
        />
      )}
      {original === 'missing' ? (
        <Text testID={VIEWER_IDS.previewLabel} style={[styles.previewLabel, { color: colors.onPrimary, backgroundColor: colors.text }]}>
          {EN['viewer.showingPreview']}
        </Text>
      ) : null}
      {original === null && progress !== null ? (
        <Text testID={VIEWER_IDS.downloading} style={[styles.previewLabel, { color: colors.onPrimary, backgroundColor: colors.text }]}>
          {progress.total > 0
            ? EN['viewer.downloadProgress'](Math.floor((progress.received / progress.total) * PERCENT))
            : EN['viewer.downloading']}
        </Text>
      ) : null}
      {original === 'failed' ? (
        <View style={styles.failedRow}>
          <Text testID={VIEWER_IDS.downloadFailed} style={[styles.failedText, { color: colors.onPrimary, backgroundColor: colors.text }]}>
            {EN['viewer.downloadFailed']}
          </Text>
          <Button
            testID={testId('viewer.retry')}
            label={EN['viewer.retry']}
            variant="secondary"
            onPress={() => {
              setOriginal(null);
              setAttempt((value) => value + 1);
            }}
          />
        </View>
      ) : null}
    </View>
  );
}

function useOwnerName(entryId: string, own: boolean): string | null {
  const { rowStore } = useAppContext();
  const [name, setName] = useState<string | null>(null);
  useEffect(() => {
    if (own) return undefined;
    let cancelled = false;
    rowStore
      .transaction(async (tx) => {
        for (const membership of await tx.memberships.byEntry(entryId)) {
          const collection = await tx.collections.get(membership.collectionId);
          if (collection !== null && collection.role === 'viewer') return collection.ownerUsername;
        }
        return null;
      })
      .then((found) => {
        if (!cancelled) setName(found);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [rowStore, entryId, own]);
  return name;
}

function useMetadata(entryId: string, summary: IndexedEntry | null): Metadata | null {
  const { services } = useAppContext();
  const version = useIndexVersion();
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  useEffect(() => {
    if (summary === null || summary.unreadable) {
      setMetadata(null);
      return undefined;
    }
    let cancelled = false;
    services.entries
      .readMetadata(entryId)
      .then((value) => {
        if (!cancelled) setMetadata(value);
      })
      .catch(() => {
        if (!cancelled) setMetadata(null);
      });
    return () => {
      cancelled = true;
    };
  }, [entryId, summary, version, services.entries]);
  return metadata;
}

export function EntryViewerScreen({ entryId }: { readonly entryId: string }) {
  const router = useRouter();
  const { services, clock } = useAppContext();
  const { colors } = useTheme();
  const window = useWindowDimensions();
  const status = useIndexStatus();
  const summary = useIndexedEntry(entryId);
  const metadata = useMetadata(entryId, summary);
  const ownerName = useOwnerName(entryId, summary?.isOwn ?? true);
  const [width, setWidth] = useState(0);
  const [page, setPage] = useState(0);
  const [zoomed, setZoomed] = useState(false);
  const [overlay, setOverlay] = useState<Overlay>('none');
  const [pinned, setPinned] = useState(false);
  useEffect(() => {
    let cancelled = false;
    services.originals
      .isPinned(entryId)
      .then((value) => {
        if (!cancelled) setPinned(value);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [entryId, services.originals]);
  const list = useRef<FlatList<Descriptor>>(null);
  const config = services.config.get();
  const sharingAvailable = config?.sharingEnabled === true || config?.publicLinksEnabled === true;

  useEffect(() => {
    if (status === 'ready' && summary === null) {
      services.galleryNotice.set({ kind: 'gone' });
      router.replace(APP_ROUTES.root);
    }
  }, [status, summary, services.galleryNotice, router]);

  const total = metadata?.media.length ?? 0;
  const goTo = useCallback(
    (next: number) => {
      if (zoomed || next < 0 || next >= total) return;
      list.current?.scrollToIndex({ index: next, animated: true });
      setPage(next);
    },
    [zoomed, total],
  );
  const onMove = useCallback((delta: number) => goTo(page + delta), [goTo, page]);
  const onBack = useCallback(() => router.back(), [router]);
  useViewerKeys({ onMove, onBack });

  if (summary === null) return <Screen testID={VIEWER_IDS.screen}>{null}</Screen>;

  const owner = summary.isOwn;
  const pageHeight = Math.min(width * PAGE_ASPECT, window.height * PAGE_MAX_SCREEN_SHARE);

  function onLayout(event: LayoutChangeEvent): void {
    setWidth(event.nativeEvent.layout.width);
  }

  function onScrollEnd(event: NativeSyntheticEvent<NativeScrollEvent>): void {
    if (width === 0) return;
    setPage(Math.round(event.nativeEvent.contentOffset.x / width));
  }

  async function trash(): Promise<void> {
    setOverlay('none');
    await services.entries.trash([entryId]);
    services.galleryNotice.set({ kind: 'trashed', entryId });
    router.replace(APP_ROUTES.root);
  }

  async function deletePermanently(): Promise<void> {
    setOverlay('none');
    await services.entries.deletePermanently([entryId]);
    router.replace(APP_ROUTES.root);
  }

  function showTag(tag: string): void {
    services.galleryFilter.set({ ...EMPTY_GALLERY_FILTER, tags: [tag] });
    router.replace(APP_ROUTES.root);
  }

  const now = new Date(clock.now());
  return (
    <Screen testID={VIEWER_IDS.screen} scroll>
      <View style={styles.header}>
        <Button testID={testId('viewer.back')} label={EN['viewer.back']} variant="secondary" onPress={onBack} />
        {!summary.trashed && !summary.unreadable ? (
          <View style={styles.actions}>
            {owner ? (
              <Button
                testID={testId('viewer.edit')}
                label={EN['viewer.edit']}
                variant="secondary"
                onPress={() => router.push(`${entryRoute(entryId)}/edit`)}
              />
            ) : null}
            <Button testID={testId('viewer.more')} label={EN['viewer.more']} variant="secondary" onPress={() => setOverlay('more')} />
          </View>
        ) : null}
      </View>
      {owner || ownerName === null ? null : (
        <Text testID={VIEWER_IDS.sharedBy} style={{ color: colors.textMuted }}>
          {EN['shared.sharedBy'](ownerName)}
        </Text>
      )}
      {summary.trashed && owner ? (
        <View style={styles.block}>
          <Banner testID={testId('viewer.inTrash')} kind="info" message={EN['viewer.inTrash']} />
          <View style={styles.actions}>
            <Button
              testID={testId('viewer.restore')}
              label={EN['viewer.restore']}
              onPress={() => services.entries.restore([entryId]).catch(() => undefined)}
            />
            <Button
              testID={testId('viewer.deletePermanently')}
              label={EN['viewer.deletePermanently']}
              variant="danger"
              onPress={() => setOverlay('confirmDelete')}
            />
          </View>
        </View>
      ) : null}
      {summary.unreadable ? (
        <View style={styles.block}>
          <Body testID={testId('viewer.unreadable')} text={EN['viewer.unreadable']} />
          {owner ? (
            <Button
              testID={testId('viewer.deletePermanently')}
              label={EN['viewer.deletePermanently']}
              variant="danger"
              onPress={() => setOverlay('confirmDelete')}
            />
          ) : null}
        </View>
      ) : null}
      {metadata === null ? null : (
        <View style={styles.block}>
          <View onLayout={onLayout}>
            {width === 0 ? null : (
              <FlatList
                ref={list}
                data={metadata.media}
                horizontal
                pagingEnabled
                scrollEnabled={!zoomed}
                showsHorizontalScrollIndicator={false}
                keyExtractor={(item) => item.mediaId}
                onMomentumScrollEnd={onScrollEnd}
                onScrollEndDrag={onScrollEnd}
                getItemLayout={(_data, index) => ({ length: width, offset: width * index, index })}
                renderItem={({ item, index }) => (
                  <MediaPage
                    entryId={entryId}
                    media={item}
                    position={index}
                    width={width}
                    height={pageHeight}
                    active={index === page}
                    near={Math.abs(index - page) <= ORIGINAL_WINDOW}
                    onZoomChange={setZoomed}
                  />
                )}
              />
            )}
          </View>
          <Text testID={TEST_IDS.viewerPageIndicator} style={[styles.indicator, { color: colors.textMuted }]}>
            {EN['viewer.pageIndicator'](page + 1, total)}
          </Text>
          <Text testID={VIEWER_IDS.time} style={[styles.time, { color: colors.text }]}>
            {formatEntryTime(metadata.takenAtUtc, metadata.utcOffsetMinutes, now.toISOString(), deviceOffsetMinutes(now))}
          </Text>
          {metadata.location === null ? null : (
            <LocationRow
              latitude={metadata.location.latitude}
              longitude={metadata.location.longitude}
              source={metadata.location.source}
            />
          )}
          {metadata.tags.length === 0 ? null : (
            <View style={styles.tags}>
              {metadata.tags.map((tag, position) => (
                <Pressable
                  key={tag}
                  testID={listTestId(VIEWER_IDS.tagChip, position)}
                  accessibilityRole="button"
                  onPress={() => showTag(tag)}
                  style={[styles.chip, { backgroundColor: colors.surface, borderColor: colors.border }]}
                >
                  <Text style={{ color: colors.text }}>{tag}</Text>
                </Pressable>
              ))}
            </View>
          )}
          <MarkdownView source={metadata.note} />
        </View>
      )}
      {overlay === 'more' ? (
        <Sheet testID={VIEWER_IDS.moreSheet} onClose={() => setOverlay('none')}>
          <Button
            testID={testId(pinned ? 'viewer.removeOffline' : 'viewer.keepOffline')}
            label={pinned ? EN['viewer.removeOffline'] : EN['viewer.keepOffline']}
            variant="secondary"
            onPress={() => {
              setOverlay('none');
              const change = pinned ? services.originals.unpinEntry(entryId) : services.originals.pinEntry(entryId);
              change.then(() => setPinned(!pinned)).catch(() => undefined);
            }}
          />
          {owner ? (
            <>
              <Button
                testID={testId('viewer.addMedia')}
                label={EN['viewer.addMedia']}
                variant="secondary"
                onPress={() => {
                  setOverlay('none');
                  router.push({ pathname: APP_ROUTES.capture, params: { entryId } });
                }}
              />
              <Button testID={testId('viewer.trash')} label={EN['viewer.trash']} variant="secondary" onPress={() => trash().catch(() => undefined)} />
            </>
          ) : null}
          {owner ? (
            <Button
              testID={testId('viewer.share')}
              label={EN['viewer.share']}
              variant="secondary"
              disabled={!sharingAvailable}
              onPress={() => setOverlay('share')}
            />
          ) : null}
          {sharingAvailable || !owner ? null : <Body testID={testId('viewer.sharingOff')} text={EN['viewer.sharingOff']} />}
        </Sheet>
      ) : null}
      {overlay === 'share' ? (
        <Sheet testID={VIEWER_IDS.moreSheet} onClose={() => setOverlay('none')}>
          <ShareEntriesSheet entryIds={[entryId]} />
        </Sheet>
      ) : null}
      {overlay === 'confirmDelete' ? (
        <Sheet testID={VIEWER_IDS.confirmSheet} onClose={() => setOverlay('none')}>
          <Text style={[styles.time, { color: colors.text }]}>{EN['bulk.confirmDeleteTitle'](1)}</Text>
          <Button
            testID={testId('bulk.confirmDelete')}
            label={EN['bulk.confirmDelete']}
            variant="danger"
            onPress={() => deletePermanently().catch(() => undefined)}
          />
          <Button testID={testId('bulk.cancel')} label={EN['bulk.cancel']} variant="secondary" onPress={() => setOverlay('none')} />
        </Sheet>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: SPACING.sm },
  actions: { flexDirection: 'row', gap: SPACING.sm, flexWrap: 'wrap' },
  block: { gap: SPACING.md },
  page: { overflow: 'hidden' },
  playOverlay: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', gap: SPACING.sm },
  failedRow: { position: 'absolute', left: SPACING.sm, right: SPACING.sm, bottom: SPACING.sm, gap: SPACING.xs, alignItems: 'flex-start' },
  failedText: { paddingHorizontal: SPACING.sm, borderRadius: RADIUS.sm, fontSize: FONT_SIZE.caption },
  previewLabel: { position: 'absolute', left: SPACING.sm, bottom: SPACING.sm, paddingHorizontal: SPACING.sm, borderRadius: RADIUS.sm, fontSize: FONT_SIZE.caption },
  indicator: { textAlign: 'center', fontSize: FONT_SIZE.caption },
  time: { fontSize: FONT_SIZE.headline, fontWeight: '600' },
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  chip: { paddingHorizontal: SPACING.md, paddingVertical: SPACING.xs, borderRadius: RADIUS.md, borderWidth: 1 },
});

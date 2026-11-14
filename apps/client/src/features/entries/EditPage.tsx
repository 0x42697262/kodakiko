import { deviceOffsetMinutes } from '@kodakiko/core';
import type { EntryMetadataV1 } from '@kodakiko/contract';
import { Image } from 'expo-image';
import { useFocusEffect, useNavigation, type Href } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { FlatList, StyleSheet, Text, View, type LayoutChangeEvent } from 'react-native';
import type { z } from 'zod';
import { useAppContext } from '../../composition/app-context';
import { useIndexVersion } from '../gallery/hooks';
import { APP_ROUTES, TABS_ROUTE_NAME, entryRoute } from '../../keys/routes';
import { ENTRY_IDS } from '../../keys/entry-ids';
import { testId } from '../../keys/test-ids';
import { useRouter } from '../../navigation/use-router';
import { EN } from '../../text/en';
import { AppBar } from '../../ui/AppBar';
import { Banner } from '../../ui/Banner';
import { IconAction } from '../../ui/IconAction';
import { Screen } from '../../ui/Screen';
import { Spinner } from '../../ui/Spinner';
import { FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { useThumbnail } from '../gallery/thumbnail';
import { TagPicker } from '../tags/tag-picker';
import { createAutosaver } from './autosaver';
import { diffDraft, draftFromMetadata, type EditDraft } from './edit-draft';
import { formatEntryTime } from './format-entry-time';
import { LocationRow } from './location-row';
import { NoteSection } from './note-section';
import { PageDots } from './page-dots';
import { TimeEditor } from './time-editor';
import { CaptionOverlay } from './caption-overlay';
import { clipboardButtonSupported, hasClipboardImage, readClipboardImage, subscribePastedFiles } from '../../adapters/clipboard-image/clipboard-image';
import type { MediaInput } from '../../adapters/media/media-toolkit';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import { Sheet } from '../gallery/sheet';
import { ShareSheet } from '../sharing/ShareSheet';
import { useVoiceRecorder } from '../../adapters/voice-recorder/voice-recorder';
import { RecordingTile, VoiceNotes } from '../voice/voice-notes';
import { VOICE_MAX_MS } from '../../constants/voice-note-constants';
import { InlineVideo } from '../video/inline-video';
import { Touchable } from '../../ui/Touchable';
import { MediaMenus, type MediaMenuState } from './media-menus';

type Metadata = z.infer<typeof EntryMetadataV1>;
type Descriptor = Metadata['media'][number];

export const EDIT_PAGE_IDS = Object.freeze({
  screen: 'edit.screen',
  pager: 'edit.pager',
  page: 'edit.page',
});
const PAGE_ASPECT = 0.75;
const PAGER_SCROLL_THROTTLE_MS = 32;

function MediaPage({
  entryId,
  media,
  width,
  position,
  caption,
  onMenu,
  active,
  onPlay,
}: {
  readonly entryId: string;
  readonly media: Descriptor;
  readonly width: number;
  readonly position: number;
  readonly caption: string | undefined;
  readonly onMenu: () => void;
  readonly active: boolean;
  readonly onPlay: () => void;
}) {
  const { colors } = useTheme();
  const uri = useThumbnail(entryId, media.mediaId);
  return (
    <View
      testID={`${EDIT_PAGE_IDS.page}.${position}`}
      style={[styles.page, { width, height: width * PAGE_ASPECT, backgroundColor: colors.surface }]}
    >
      {uri === null ? null : <Image source={{ uri }} style={styles.image} contentFit="contain" />}
      {media.kind === 'video' ? (
        <Touchable testID={`edit.play.${position}`} accessibilityRole="button" accessibilityLabel={EN['viewer.play']} onPress={onPlay} style={StyleSheet.absoluteFill}>
          <InlineVideo entryId={entryId} mediaId={media.mediaId} active={active} testID={`edit.inlineVideo.${position}`} />
        </Touchable>
      ) : null}
      {caption === undefined ? null : <CaptionOverlay testID={`edit.captionText.${position}`} caption={caption} />}
      <View style={styles.pageMenu}>
        <IconAction testID={`edit.mediaMenu.${position}`} icon="overflow" label={EN['edit.mediaOptions']} onPress={onMenu} />
      </View>
    </View>
  );
}

export function EditPage({
  entryId,
  isNew,
}: {
  readonly entryId: string;
  readonly isNew: boolean;
}) {
  const router = useRouter();
  const navigation = useNavigation();
  const { services, clock, tempFiles } = useAppContext();
  const { colors } = useTheme();
  const indexVersion = useIndexVersion();
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [draft, setDraft] = useState<EditDraft | null>(null);
  const [favorite, setFavorite] = useState(false);
  const [page, setPage] = useState(0);
  const [width, setWidth] = useState(0);
  const [editingTime, setEditingTime] = useState(false);
  const [failed, setFailed] = useState(false);
  const pager = useRef<FlatList<Descriptor>>(null);
  const [menu, setMenu] = useState<MediaMenuState>(null);
  const [sharing, setSharing] = useState(false);
  const [voiceFailed, setVoiceFailed] = useState(false);
  const voice = useVoiceRecorder();
  const stopVoice = useRef<(() => void) | null>(null);
  useEffect(() => {
    if (voice.recording && voice.elapsedMs >= VOICE_MAX_MS) stopVoice.current?.();
  }, [voice.recording, voice.elapsedMs]);
  const [hasImage, setHasImage] = useState(false);
  const config = services.config.get();
  const sharingAvailable = config?.sharingEnabled === true || config?.publicLinksEnabled === true;

  const saver = useMemo(
    () =>
      createAutosaver<EditDraft>(
        async (next) => {
          const current = await services.entries.readMetadata(entryId);
          const diff = diffDraft(current, next);
          for (const mediaId of diff.removedMediaIds)
            await services.entries.removeMedia(entryId, mediaId);
          if (Object.keys(diff.edit).length > 0) await services.entries.edit(entryId, diff.edit);
          setFailed(false);
        },
        () => setFailed(true),
      ),
    [entryId, services.entries],
  );

  const reload = useCallback(() => {
    let cancelled = false;
    saver
      .flush()
      .then(() => services.entries.readMetadata(entryId))
      .then((value) => {
        if (cancelled) return;
        setMetadata(value);
        setDraft(draftFromMetadata(value));
        setFavorite(value.favorite === true);
      })
      .catch(() => {
        if (!cancelled) router.back();
      });
    return () => {
      cancelled = true;
    };
  }, [entryId, saver, services.entries, router]);

  useFocusEffect(reload);

  const addInputs = useCallback(
    async (inputs: readonly MediaInput[]) => {
      await saver.flush();
      for (const input of inputs) {
        const current = await services.entries.readMetadata(entryId);
        const limit = (services.config.get() ?? CONFIG_FALLBACK).maxMediaPerEntry;
        const outcome = await services.mediaImport.importOne(input, { used: current.media.length, limit });
        if (outcome.ok) await services.entries.addMedia(entryId, [outcome.media]);
        else setFailed(true);
      }
      reload();
    },
    [entryId, reload, saver, services],
  );

  useFocusEffect(
    useCallback(() => {
      hasClipboardImage()
        .then(setHasImage)
        .catch(() => setHasImage(false));
      return subscribePastedFiles((inputs) => {
        addInputs(inputs).catch(() => setFailed(true));
      });
    }, [addInputs]),
  );

  async function pasteFromClipboard(): Promise<void> {
    const input = await readClipboardImage(tempFiles);
    if (input !== null) await addInputs([input]);
  }

  const mediaById = useMemo(
    () => new Map<string, Descriptor>((metadata?.media ?? []).map((item) => [item.mediaId, item])),
    [metadata, indexVersion],
  );

  if (metadata === null || draft === null) {
    return (
      <Screen testID={EDIT_PAGE_IDS.screen}>
        <Spinner testID="edit.loading" />
      </Screen>
    );
  }

  const media = draft.mediaIds
    .map((mediaId) => mediaById.get(mediaId))
    .filter((item): item is Descriptor => item !== undefined);
  const visual = media.filter((item) => item.kind !== 'audio');
  const current = Math.min(page, Math.max(0, visual.length - 1));

  async function toggleRecording(): Promise<void> {
    if (!voice.recording) {
      await voice.start();
      return;
    }
    const recorded = await voice.stop();
    if (recorded === null) return;
    await saver.flush();
    const currentMeta = await services.entries.readMetadata(entryId);
    const limit = (services.config.get() ?? CONFIG_FALLBACK).maxMediaPerEntry;
    const outcome = await services.mediaImport.importVoiceNote(recorded.input, { durationMs: recorded.durationMs, mimeType: recorded.mimeType }, { used: currentMeta.media.length, limit });
    if (!outcome.ok) throw new Error(outcome.reason);
    await services.entries.addMedia(entryId, [outcome.media]);
    reload();
  }
  stopVoice.current = () => {
    toggleRecording().catch(() => setVoiceFailed(true));
  };

  function change(patch: Partial<EditDraft>, debounced = false): void {
    if (draft === null) return;
    const next = { ...draft, ...patch };
    setDraft(next);
    saver.change(next, debounced);
  }

  async function leave(): Promise<void> {
    await saver.flush();
    services.savedNotice.set(clock.now());
    if (!isNew) {
      router.back();
      return;
    }
    const firstRoute = navigation.getState()?.routes[0]?.name;
    if (router.canDismiss()) router.dismissAll();
    if (firstRoute !== TABS_ROUTE_NAME) router.replace(APP_ROUTES.root as Href);
  }

  function openCapture(mode: 'photo' | 'video'): void {
    saver
      .flush()
      .then(() => router.push({ pathname: APP_ROUTES.capture, params: { entryId, mode } } as Href))
      .catch(() => undefined);
  }

  async function toggleFavorite(): Promise<void> {
    const next = !favorite;
    setFavorite(next);
    await saver.flush();
    await services.entries.setFavorite([entryId], next);
  }

  async function trash(): Promise<void> {
    await saver.flush();
    await services.entries.trash([entryId]);
    services.galleryNotice.set({ kind: 'trashed', entryId });
    if (router.canDismiss()) router.dismissAll();
    else router.replace(APP_ROUTES.root as Href);
  }

  function goTo(index: number): void {
    setPage(index);
    pager.current?.scrollToOffset({ offset: index * width, animated: true });
  }

  const original = { takenAtUtc: metadata.takenAtUtc, utcOffsetMinutes: metadata.utcOffsetMinutes };

  return (
    <Screen testID={isNew ? ENTRY_IDS.composeScreen : EDIT_PAGE_IDS.screen} scroll>
      <AppBar
        title={isNew ? EN['compose.title'] : EN['edit.title']}
        backTestID={testId('compose.back')}
        onBack={() => leave().catch(() => undefined)}
        actions={[
          {
            key: 'done',
            icon: 'save',
            label: EN['edit.done'],
            testID: testId('compose.save'),
            onPress: () => leave().catch(() => undefined),
          },
        ]}
        menuTestID="edit.more"
        menu={[
          {
            key: 'view',
            label: EN['edit.viewEntry'],
            testID: 'edit.view',
            onPress: () => router.replace(entryRoute(entryId) as Href),
          },
          {
            key: 'trash',
            label: EN['edit.trash'],
            testID: 'edit.trash',
            onPress: () => trash().catch(() => undefined),
          },
        ]}
      />

      <View onLayout={(event: LayoutChangeEvent) => setWidth(event.nativeEvent.layout.width)}>
        {width === 0 ? null : (
          <FlatList
            ref={pager}
            testID={EDIT_PAGE_IDS.pager}
            data={visual}
            horizontal
            pagingEnabled
            showsHorizontalScrollIndicator={false}
            keyExtractor={(item) => item.mediaId}
            getItemLayout={(_data, index) => ({ length: width, offset: width * index, index })}
            onMomentumScrollEnd={(event) =>
              setPage(Math.round(event.nativeEvent.contentOffset.x / width))
            }
            onScroll={(event) => setPage(Math.round(event.nativeEvent.contentOffset.x / width))}
            scrollEventThrottle={PAGER_SCROLL_THROTTLE_MS}
            renderItem={({ item, index }) => (
              <MediaPage
                entryId={entryId}
                media={item}
                width={width}
                position={index}
                caption={draft.captions[item.mediaId]}
                onMenu={() => setMenu({ kind: 'menu', mediaId: item.mediaId })}
                active={index === current}
                onPlay={() => {
                  if (services.videoLauncher.canPlay()) services.videoLauncher.open(entryId, item.mediaId);
                }}
              />
            )}
          />
        )}
      </View>
      <PageDots count={visual.length} active={current} onSelect={goTo} />

      <View style={styles.actions}>
        <IconAction
          testID="edit.takePhoto"
          icon="camera"
          label={EN['edit.takePhoto']}
          onPress={() => openCapture('photo')}
        />
        <IconAction
          testID="edit.recordVideo"
          icon="video"
          label={EN['edit.recordVideo']}
          onPress={() => openCapture('video')}
        />
        <IconAction
          testID="edit.addFromDevice"
          icon="photoAdd"
          label={EN['edit.addFromDevice']}
          onPress={() => openCapture('photo')}
        />
        <IconAction
          testID="edit.favorite"
          icon={favorite ? 'favorite' : 'favoriteOff'}
          label={favorite ? EN['edit.unfavorite'] : EN['edit.favorite']}
          onPress={() => toggleFavorite().catch(() => undefined)}
        />
        {clipboardButtonSupported && hasImage ? (
          <IconAction testID="edit.paste" icon="paste" label={EN['edit.paste']} onPress={() => pasteFromClipboard().catch(() => undefined)} />
        ) : null}
        {voice.supported ? (
          <IconAction
            testID="edit.recordVoice"
            icon={voice.recording ? 'stop' : 'mic'}
            label={voice.recording ? EN['voice.stop'] : EN['voice.record']}
            onPress={() => {
              setVoiceFailed(false);
              toggleRecording().catch(() => setVoiceFailed(true));
            }}
          />
        ) : null}
        <IconAction testID="edit.share" icon="share" label={EN['edit.share']} onPress={() => setSharing(true)} />
      </View>
      {voice.recording ? (
        <RecordingTile
          elapsedMs={voice.elapsedMs}
          onStop={() => {
            toggleRecording().catch(() => setVoiceFailed(true));
          }}
        />
      ) : null}
      {voiceFailed ? <Banner testID="voice.failed" kind="error" message={EN['voice.failed']} /> : null}
      <VoiceNotes
        entryId={entryId}
        media={media}
        onRemove={
          media.length <= 1
            ? null
            : (mediaId) => change({ mediaIds: draft.mediaIds.filter((id) => id !== mediaId) })
        }
      />

      <View style={styles.section}>
        <Text style={[styles.label, { color: colors.textMuted }]}>{EN['compose.when']}</Text>
        <View style={styles.row}>
          <Text testID={ENTRY_IDS.composeWhenValue} style={[styles.value, { color: colors.text }]}>
            {formatEntryTime(
              draft.time.takenAtUtc,
              draft.time.utcOffsetMinutes,
              clock.nowIso(),
              deviceOffsetMinutes(new Date(clock.now())),
            )}
          </Text>
          <IconAction
            testID={testId('compose.change')}
            icon="edit"
            label={EN['edit.changeTime']}
            tonal={false}
            onPress={() => setEditingTime(!editingTime)}
          />
        </View>
        {editingTime ? (
          <TimeEditor
            value={draft.time}
            original={original}
            onDone={(value) => {
              change({ time: value });
              setEditingTime(false);
            }}
          />
        ) : null}
      </View>

      {draft.location === null ? null : (
        <View style={styles.section}>
          <Text style={[styles.label, { color: colors.textMuted }]}>{EN['compose.where']}</Text>
          <LocationRow
            latitude={draft.location.latitude}
            longitude={draft.location.longitude}
            source={draft.location.source}
            onRemove={() => change({ location: null })}
          />
        </View>
      )}

      <View style={styles.section}>
        <Text style={[styles.label, { color: colors.textMuted }]}>{EN['compose.tags']}</Text>
        <TagPicker tags={draft.tags} onChange={(tags) => change({ tags })} />
      </View>

      <View style={styles.section}>
        <Text style={[styles.label, { color: colors.textMuted }]}>{EN['compose.note']}</Text>
        <NoteSection note={draft.note} onChange={(note) => change({ note }, true)} />
      </View>

      {failed ? (
        <Banner testID={testId('edit.failed')} kind="error" message={EN['edit.failed']} />
      ) : null}
      {sharing ? (
        <Sheet testID="edit.shareSheet" onClose={() => setSharing(false)}>
          <ShareSheet entryIds={[entryId]} inKodakiko={sharingAvailable} />
        </Sheet>
      ) : null}
      <MediaMenus
        entryId={entryId}
        state={menu}
        setState={setMenu}
        media={media}
        captions={draft.captions}
        flush={() => saver.flush()}
        onCaption={(mediaId, caption) => {
          const { [mediaId]: _previous, ...rest } = draft.captions;
          change({ captions: caption === '' ? rest : { ...rest, [mediaId]: caption } });
        }}
        onCover={(mediaId) => {
          change({ mediaIds: [mediaId, ...draft.mediaIds.filter((id) => id !== mediaId)] });
          goTo(0);
        }}
        onRemove={(mediaId) => {
          change({ mediaIds: draft.mediaIds.filter((id) => id !== mediaId) });
          setPage(0);
        }}
        onReplaced={reload}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  page: {
    borderRadius: RADIUS.md,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  image: { width: '100%', height: '100%' },
  pageMenu: { position: 'absolute', top: SPACING.sm, right: SPACING.sm },
  actions: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    flexWrap: 'wrap',
    gap: SPACING.sm,
  },
  section: { gap: SPACING.xs },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: SPACING.sm,
  },
  label: { fontSize: FONT_SIZE.caption },
  value: { fontSize: FONT_SIZE.headline, flexShrink: 1 },
});

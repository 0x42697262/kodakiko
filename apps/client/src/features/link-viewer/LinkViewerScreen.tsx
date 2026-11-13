import { useEvent } from 'expo';
import { Image } from 'expo-image';
import { useVideoPlayer, VideoView } from 'expo-video';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import { ApiError } from '../../adapters/http/http';
import { createMediaWorker, type MediaWorker } from '../../adapters/video-source/media-worker.web';
import { MEDIA_STREAM_PATH, WEB_BLOB_PLAYBACK_MAX_BYTES } from '../../constants/video-constants';
import { listTestId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { Spinner } from '../../ui/Spinner';
import { Body, Title } from '../../ui/Typography';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { MarkdownView } from '../markdown/markdown-view';
import { parseLinkFragment } from '../../services/links/link-url';
import { createLinkSession, type LinkItem, type LinkSession, type OpenedLink } from './link-session';
import { usePublicViewer } from './public-viewer-context';

const TILE = 120;
const IMAGE_CACHE_ITEMS = 200;
const DOWNLOAD_PREFIX = 'kodakiko-';
const PHOTO_EXTENSION = 'jpg';
const PLAYBACK_ID_BYTES = 16;
const DOWNLOAD_MAX_BYTES = 209_715_200;
const MEDIA_SCREEN_SHARE = 0.6;
const MINUTE_MS = 60_000;
const DATE_CHARS = 10;
const BINARY_EXTENSION = 'bin';
const LINK_IDS = Object.freeze({
  screen: 'linkViewer.screen',
  item: 'linkViewer.item',
  title: 'linkViewer.title',
  message: 'linkViewer.message',
  detail: 'linkViewer.detail',
  download: 'linkViewer.download',
  next: 'linkViewer.next',
  previous: 'linkViewer.previous',
  back: 'linkViewer.back',
  play: 'linkViewer.play',
  note: 'linkViewer.note',
  footer: 'linkViewer.footer',
});

type ViewState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'failed'; readonly message: string }
  | { readonly kind: 'ready'; readonly link: OpenedLink };

function failureMessage(error: unknown, maxViews: number | null): string {
  if (!(error instanceof ApiError)) return EN['links.viewerInvalid'];
  if (error.code === 'LINK_EXPIRED') return EN['links.viewerExpired'];
  if (error.code === 'LINK_VIEW_EXPIRED') return maxViews === 1 ? EN['links.viewerOneViewEnded'] : EN['links.viewerSessionEnded'];
  return EN['links.viewerInvalid'];
}

function useObjectUrls() {
  const urls = useRef(new Map<string, string>());
  useEffect(() => {
    const owned = urls.current;
    return () => {
      for (const url of owned.values()) URL.revokeObjectURL(url);
      owned.clear();
    };
  }, []);
  return useCallback((key: string, bytes: Uint8Array, type: string) => {
    const existing = urls.current.get(key);
    if (existing !== undefined) return existing;
    const url = URL.createObjectURL(new Blob([bytes.slice()], { type }));
    urls.current.set(key, url);
    while (urls.current.size > IMAGE_CACHE_ITEMS) {
      const oldest = urls.current.keys().next();
      if (oldest.done === true) break;
      URL.revokeObjectURL(urls.current.get(oldest.value) ?? '');
      urls.current.delete(oldest.value);
    }
    return url;
  }, []);
}

function GridTile({ item, session, urlFor, onOpen }: {
  readonly item: LinkItem;
  readonly session: LinkSession;
  readonly urlFor: (key: string, bytes: Uint8Array, type: string) => string;
  readonly onOpen: () => void;
}) {
  const { colors } = useTheme();
  const [uri, setUri] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    session
      .thumbnail(item)
      .then((bytes) => {
        if (active) setUri(urlFor(`thumb:${item.thumbnail.id}`, bytes, item.descriptor.thumbnail.mimeType));
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [item, session, urlFor]);
  return (
    <Pressable
      testID={listTestId(LINK_IDS.item, item.position)}
      accessibilityRole="button"
      onPress={onOpen}
      style={[styles.tile, { backgroundColor: colors.surface }]}
    >
      {uri === null ? null : <Image source={{ uri }} style={styles.fill} contentFit="cover" />}
      {item.descriptor.kind === 'video' ? <Text style={[styles.badge, { color: colors.onPrimary, backgroundColor: colors.text }]}>{'\u25B6'}</Text> : null}
    </Pressable>
  );
}

const HEX_RADIX = 16;
const HEX_WIDTH = 2;

function hexId(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(HEX_RADIX).padStart(HEX_WIDTH, '0')).join('');
}

function VideoPane({ item, session, worker, playbacks }: {
  readonly item: LinkItem;
  readonly session: LinkSession;
  readonly worker: MediaWorker;
  readonly playbacks: Map<string, string>;
}) {
  const { sodium } = usePublicViewer();
  const [uri, setUri] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let cancelled = false;
    let release: (() => void) | null = null;
    (async () => {
      if (await worker.isAvailable()) {
        const registration = session.video(item);
        const playbackId = hexId(sodium.randomBytes(PLAYBACK_ID_BYTES));
        playbacks.set(playbackId, registration.objectId);
        const key = registration.key.slice().buffer;
        worker.post({ type: 'register', playbackId, key, objectId: registration.objectId, downloadUrl: registration.downloadUrl, extraHeaders: registration.extraHeaders, plaintextSize: registration.plaintextSize, ciphertextSize: registration.ciphertextSize, mimeType: registration.mimeType }, [key]);
        registration.key.fill(0);
        release = () => {
          playbacks.delete(playbackId);
          worker.post({ type: 'forget', playbackId });
        };
        if (!cancelled) setUri(`${MEDIA_STREAM_PATH}${playbackId}`);
        return;
      }
      if (item.descriptor.originalPlaintextBytes > WEB_BLOB_PLAYBACK_MAX_BYTES) throw new Error('too large');
      const bytes = await session.original(item);
      const url = URL.createObjectURL(new Blob([bytes.slice()], { type: item.descriptor.mimeType }));
      release = () => URL.revokeObjectURL(url);
      if (!cancelled) setUri(url);
    })().catch(() => {
      if (!cancelled) setFailed(true);
    });
    return () => {
      cancelled = true;
      release?.();
    };
  }, [item, session, worker, sodium, playbacks]);
  if (failed) return <Body text={EN['links.viewerVideoUnsupported']} />;
  if (uri === null) return <Spinner testID="linkViewer.spinner" />;
  return <PublicPlayer uri={uri} />;
}

function PublicPlayer({ uri }: { readonly uri: string }) {
  const player = useVideoPlayer(uri, (instance) => {
    instance.loop = false;
    instance.play();
  });
  useEvent(player, 'statusChange');
  return (
    <View testID="video.player" style={styles.video}>
      <VideoView player={player} nativeControls contentFit="contain" style={styles.fill} />
    </View>
  );
}

function Detail({ items, index, session, urlFor, worker, playbacks, onMove, onClose }: {
  readonly items: readonly LinkItem[];
  readonly index: number;
  readonly session: LinkSession;
  readonly urlFor: (key: string, bytes: Uint8Array, type: string) => string;
  readonly worker: MediaWorker;
  readonly playbacks: Map<string, string>;
  readonly onMove: (next: number) => void;
  readonly onClose: () => void;
}) {
  const { colors } = useTheme();
  const window = useWindowDimensions();
  const item = items[index];
  const [photoUri, setPhotoUri] = useState<string | null>(null);
  const [playing, setPlaying] = useState(false);

  useEffect(() => {
    setPlaying(false);
    setPhotoUri(null);
    if (item === undefined || item.descriptor.kind !== 'photo') return undefined;
    let active = true;
    session
      .original(item)
      .then((bytes) => {
        if (active) setPhotoUri(urlFor(`orig:${item.original.id}`, bytes, item.descriptor.mimeType));
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [item, session, urlFor]);

  if (item === undefined) return null;

  async function download(): Promise<void> {
    if (item === undefined || item.descriptor.originalPlaintextBytes > DOWNLOAD_MAX_BYTES) return;
    const bytes = await session.original(item);
    const url = URL.createObjectURL(new Blob([bytes.slice()], { type: item.descriptor.mimeType }));
    const anchor = document.createElement('a');
    const extension = item.descriptor.kind === 'photo' ? PHOTO_EXTENSION : item.descriptor.mimeType.split('/')[1] ?? BINARY_EXTENSION;
    anchor.href = url;
    anchor.download = `${DOWNLOAD_PREFIX}${item.position + 1}.${extension}`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  const height = Math.min(window.height * MEDIA_SCREEN_SHARE, window.width);
  return (
    <View testID={LINK_IDS.detail} style={styles.detail}>
      <View style={styles.row}>
        <Button testID={LINK_IDS.back} label={EN['links.viewerBack']} variant="secondary" onPress={onClose} />
        <Button testID={LINK_IDS.previous} label={EN['links.viewerPrevious']} variant="secondary" disabled={index === 0} onPress={() => onMove(index - 1)} />
        <Button testID={LINK_IDS.next} label={EN['links.viewerNext']} variant="secondary" disabled={index >= items.length - 1} onPress={() => onMove(index + 1)} />
        <Button testID={LINK_IDS.download} label={EN['links.viewerDownload']} variant="secondary" onPress={() => download().catch(() => undefined)} />
      </View>
      <View style={[styles.media, { height, backgroundColor: colors.surface }]}>
        {item.descriptor.kind === 'photo' ? (
          photoUri === null ? <Spinner testID="linkViewer.spinner" /> : <Image source={{ uri: photoUri }} style={styles.fill} contentFit="contain" />
        ) : playing ? (
          <VideoPane item={item} session={session} worker={worker} playbacks={playbacks} />
        ) : (
          <Button testID={LINK_IDS.play} label={EN['links.viewerPlay']} onPress={() => setPlaying(true)} />
        )}
      </View>
      <Text style={{ color: colors.textMuted }}>{new Date(Date.parse(item.takenAtUtc) + item.utcOffsetMinutes * MINUTE_MS).toISOString().slice(0, DATE_CHARS)}</Text>
      {item.note.length === 0 ? null : (
        <View testID={LINK_IDS.note}>
          <MarkdownView source={item.note} />
        </View>
      )}
    </View>
  );
}

export function LinkViewerScreen({ linkId }: { readonly linkId: string }) {
  const { sodium, http } = usePublicViewer();
  const { colors } = useTheme();
  const [state, setState] = useState<ViewState>({ kind: 'loading' });
  const [index, setIndex] = useState<number | null>(null);
  const urlFor = useObjectUrls();
  const [session, setSession] = useState<LinkSession | null>(null);
  const sessionRef = useRef<LinkSession | null>(null);
  const playbacks = useMemo(() => new Map<string, string>(), []);
  const worker = useMemo(() => {
    const created = createMediaWorker(
      {
        onNeedKey: () => undefined,
        onNeedUrl: (playbackId) => {
          const objectId = playbacks.get(playbackId);
          if (objectId === undefined) return;
          sessionRef.current
            ?.refreshUrl(objectId)
            .then((downloadUrl) => {
              if (downloadUrl !== null) created.post({ type: 'refreshUrl', playbackId, downloadUrl });
            })
            .catch(() => undefined);
        },
      },
      () => hexId(sodium.randomBytes(PLAYBACK_ID_BYTES)),
    );
    return created;
  }, [sodium, playbacks]);

  useEffect(() => {
    const secret = parseLinkFragment(window.location.hash);
    window.history.replaceState(null, '', window.location.pathname);
    if (secret === null) {
      setState({ kind: 'failed', message: EN['links.viewerIncomplete'] });
      return;
    }
    const created = createLinkSession({ sodium, http, linkId, secret, origin: window.location.origin });
    secret.fill(0);
    sessionRef.current = created;
    setSession(created);
    created
      .open()
      .then((link) => {
        document.title = link.title;
        setState({ kind: 'ready', link });
      })
      .catch((error: unknown) => setState({ kind: 'failed', message: failureMessage(error, null) }));
  }, [sodium, http, linkId]);

  if (state.kind === 'loading' || session === null) {
    return (
      <Screen testID={LINK_IDS.screen}>
        {state.kind === 'failed' ? <Body testID={LINK_IDS.message} text={state.message} /> : <Body text={EN['links.viewerLoading']} />}
      </Screen>
    );
  }
  if (state.kind === 'failed') {
    return (
      <Screen testID={LINK_IDS.screen}>
        <Body testID={LINK_IDS.message} text={state.message} />
      </Screen>
    );
  }
  const { link } = state;
  return (
    <Screen testID={LINK_IDS.screen} scroll>
      <Title testID={LINK_IDS.title} text={link.title} />
      {index === null ? (
        <View style={styles.grid}>
          {link.items.map((item) => (
            <GridTile key={item.original.id} item={item} session={session} urlFor={urlFor} onOpen={() => setIndex(item.position)} />
          ))}
        </View>
      ) : (
        <Detail items={link.items} index={index} session={session} urlFor={urlFor} worker={worker} playbacks={playbacks} onMove={setIndex} onClose={() => setIndex(null)} />
      )}
      <Text testID={LINK_IDS.footer} style={[styles.footer, { color: colors.textMuted }]}>
        {EN['links.viewerFooter']}
      </Text>
    </Screen>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.xs },
  tile: { width: TILE, height: TILE, overflow: 'hidden' },
  fill: { width: '100%', height: '100%' },
  badge: { position: 'absolute', left: SPACING.xs, bottom: SPACING.xs, paddingHorizontal: SPACING.xs, fontSize: FONT_SIZE.caption },
  detail: { gap: SPACING.md },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  media: { width: '100%', alignItems: 'center', justifyContent: 'center' },
  video: { width: '100%', height: '100%' },
  footer: { fontSize: FONT_SIZE.caption, textAlign: 'center', paddingVertical: SPACING.lg },
});

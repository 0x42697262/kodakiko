import { useIsFocused } from 'expo-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { useAppContext } from '../../composition/app-context';
import type { PreparedVideo } from '../../services/video/video-source';
import { shouldAutoplay } from './autoplay';
import { InlinePlayer } from './inline-player';

export const AUTOPLAY_SETTLE_MS = 800;

function useCached(mediaId: string): boolean {
  const { services, rowStore } = useAppContext();
  const [cached, setCached] = useState(false);
  useEffect(() => {
    let cancelled = false;
    services.originals
      .originalObjectId(mediaId)
      .then(async (objectId) => {
        if (objectId === null) return false;
        const row = await rowStore.transaction((tx) => tx.objects.get(objectId));
        return row?.localState === 'present';
      })
      .then((value) => {
        if (!cancelled) setCached(value);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [mediaId, services.originals, rowStore]);
  return cached;
}

function useWifi(): boolean {
  const { services } = useAppContext();
  const [wifi, setWifi] = useState(false);
  useEffect(() => {
    let cancelled = false;
    const check = () => {
      services.network
        .isWifi()
        .then((value) => {
          if (!cancelled) setWifi(value);
        })
        .catch(() => undefined);
    };
    check();
    const unsubscribe = services.network.subscribe(check);
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, [services.network]);
  return wifi;
}

export function InlineVideo({ entryId, mediaId, active, testID }: { readonly entryId: string; readonly mediaId: string; readonly active: boolean; readonly testID: string }) {
  const focused = useIsFocused();
  const { services } = useAppContext();
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const cached = useCached(mediaId);
  const wifi = useWifi();
  const wanted = shouldAutoplay(settings, { active: active && focused, cached, wifi });
  const [prepared, setPrepared] = useState<PreparedVideo | null>(null);
  const [requested, setRequested] = useState(false);

  useEffect(() => {
    if (!focused) {
      setRequested(false);
      return undefined;
    }
    if (!wanted) return undefined;
    const timer = setTimeout(() => setRequested(true), AUTOPLAY_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [wanted, focused]);

  useEffect(() => {
    if (!requested) return undefined;
    const controller = new AbortController();
    let current: PreparedVideo | null = null;
    services.videoSource
      .prepare({ entryId, mediaId, onProgress: () => undefined, signal: controller.signal })
      .then((video) => {
        current = video;
        if (controller.signal.aborted) video.release().catch(() => undefined);
        else setPrepared(video);
      })
      .catch(() => undefined);
    return () => {
      controller.abort();
      current?.release().catch(() => undefined);
    };
  }, [requested, entryId, mediaId, services.videoSource]);

  useEffect(() => {
    if (!requested) setPrepared(null);
  }, [requested]);

  if (prepared === null || !requested) return null;
  return <InlinePlayer uri={prepared.uri} active={wanted} testID={testID} />;
}


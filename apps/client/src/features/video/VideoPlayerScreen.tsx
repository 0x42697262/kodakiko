import { useRouter } from 'expo-router';
import { useEventListener } from 'expo';
import { useVideoPlayer, VideoView } from 'expo-video';
import { useCallback, useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { COLORS_ON_MEDIA, VIDEO_IDS } from '../../constants/video-ui';
import { testId } from '../../keys/test-ids';
import type { PreparedVideo } from '../../services/video/video-source';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useViewerKeys } from '../viewer/use-viewer-keys';
import { prepareProblem, type VideoProblem } from './video-errors';

const PERCENT = 100;

function Player({ uri, onProblem }: { readonly uri: string; readonly onProblem: (problem: VideoProblem) => void }) {
  const player = useVideoPlayer(uri, (instance) => {
    instance.loop = false;
    instance.play();
  });
  useEventListener(player, 'statusChange', ({ status, error }) => {
    if (status !== 'error') return;
    const gone = error?.message.includes('410') ?? false;
    onProblem(gone ? { message: EN['video.stopped'], retry: true } : { message: EN['video.formatUnsupported'], retry: false });
  });
  return (
    <View testID={VIDEO_IDS.view} style={styles.video}>
      <VideoView player={player} nativeControls contentFit="contain" fullscreenOptions={{ enable: true }} style={styles.video} />
    </View>
  );
}

export function VideoPlayerScreen({ entryId, mediaId }: { readonly entryId: string; readonly mediaId: string }) {
  const router = useRouter();
  const { services } = useAppContext();
  const [prepared, setPrepared] = useState<PreparedVideo | null>(null);
  const [progress, setProgress] = useState(0);
  const [problem, setProblem] = useState<VideoProblem | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let current: PreparedVideo | null = null;
    setProblem(null);
    setPrepared(null);
    services.videoSource
      .prepare({ entryId, mediaId, onProgress: setProgress, signal: controller.signal })
      .then((video) => {
        current = video;
        if (controller.signal.aborted) video.release().catch(() => undefined);
        else setPrepared(video);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setProblem(prepareProblem(error));
      });
    return () => {
      controller.abort();
      current?.release().catch(() => undefined);
    };
  }, [entryId, mediaId, attempt, services.videoSource]);

  const close = useCallback(() => router.back(), [router]);
  useViewerKeys({ onMove: () => undefined, onBack: close });

  return (
    <View testID={VIDEO_IDS.screen} style={styles.screen}>
      {prepared === null || problem !== null ? null : <Player uri={prepared.uri} onProblem={setProblem} />}
      {prepared === null && problem === null ? (
        <Text testID={VIDEO_IDS.preparing} style={styles.text}>
          {progress > 0 && progress < 1 ? EN['video.preparing'](Math.floor(progress * PERCENT)) : EN['video.loading']}
        </Text>
      ) : null}
      {problem === null ? null : (
        <View style={styles.problem}>
          <Text testID={VIDEO_IDS.problem} style={styles.text}>
            {problem.message}
          </Text>
          {problem.retry ? (
            <Button testID={testId('video.retry')} label={EN['video.retry']} onPress={() => setAttempt((value) => value + 1)} />
          ) : null}
        </View>
      )}
      <View style={styles.close}>
        <Button testID={testId('video.close')} label={EN['video.close']} variant="secondary" onPress={close} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: COLORS_ON_MEDIA.background, alignItems: 'center', justifyContent: 'center' },
  video: { width: '100%', height: '100%' },
  text: { color: COLORS_ON_MEDIA.text, fontSize: FONT_SIZE.body, textAlign: 'center', padding: SPACING.lg },
  problem: { alignItems: 'center', gap: SPACING.md },
  close: { position: 'absolute', top: SPACING.xl, right: SPACING.lg },
});

import { useAudioPlayer, useAudioPlayerStatus } from 'expo-audio';
import { useEffect } from 'react';
import { EN } from '../../text/en';
import { IconAction } from '../../ui/IconAction';

export function VoicePlayer({ uri, testID, autoPlay }: { readonly uri: string; readonly testID: string; readonly autoPlay: boolean }) {
  const player = useAudioPlayer(uri);
  const status = useAudioPlayerStatus(player);
  useEffect(() => {
    if (autoPlay) player.play();
  }, [autoPlay, player]);
  useEffect(() => {
    if (status.didJustFinish) player.seekTo(0).catch(() => undefined);
  }, [status.didJustFinish, player]);
  return (
    <IconAction
      testID={testID}
      icon={status.playing ? 'pause' : 'play'}
      label={status.playing ? EN['voice.pause'] : EN['voice.play']}
      onPress={() => (status.playing ? player.pause() : player.play())}
    />
  );
}

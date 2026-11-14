import { useVideoPlayer, VideoView } from 'expo-video';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { MuteButton } from './mute-button';

export function InlinePlayer({ uri, active, testID }: { readonly uri: string; readonly active: boolean; readonly testID: string }) {
  const [muted, setMuted] = useState(true);
  const player = useVideoPlayer(uri, (instance) => {
    instance.loop = true;
    instance.muted = true;
  });
  useEffect(() => {
    if (active) player.play();
    else player.pause();
  }, [active, player]);
  useEffect(() => {
    player.muted = muted;
  }, [muted, player]);
  return (
    <View testID={testID} style={StyleSheet.absoluteFill}>
      <VideoView player={player} nativeControls={false} contentFit="contain" style={StyleSheet.absoluteFill} />
      <MuteButton testID={`${testID}.mute`} muted={muted} onToggle={() => setMuted(!muted)} />
    </View>
  );
}

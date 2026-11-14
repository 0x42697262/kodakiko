import { createElement, useEffect, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { MuteButton } from './mute-button';

const FILL = Object.freeze({ width: '100%', height: '100%', objectFit: 'contain' });

export function InlinePlayer({ uri, active, testID }: { readonly uri: string; readonly active: boolean; readonly testID: string }) {
  const [muted, setMuted] = useState(true);
  const video = useRef<HTMLVideoElement | null>(null);
  useEffect(() => {
    const element = video.current;
    if (element === null) return;
    if (active) element.play().catch(() => undefined);
    else element.pause();
  }, [active, uri]);
  useEffect(() => {
    if (video.current !== null) video.current.muted = muted;
  }, [muted]);
  return (
    <View testID={testID} style={StyleSheet.absoluteFill}>
      {createElement('video', { ref: video, src: uri, muted: true, loop: true, playsInline: true, style: FILL })}
      <MuteButton testID={`${testID}.mute`} muted={muted} onToggle={() => setMuted(!muted)} />
    </View>
  );
}

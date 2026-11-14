import { createElement, useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { EN } from '../../text/en';
import { IconAction } from '../../ui/IconAction';

const HIDDEN = Object.freeze({ display: 'none' });

export function VoicePlayer({ uri, testID, autoPlay }: { readonly uri: string; readonly testID: string; readonly autoPlay: boolean }) {
  const audio = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  useEffect(() => {
    if (autoPlay) audio.current?.play().catch(() => undefined);
  }, [autoPlay, uri]);
  return (
    <View>
      {createElement('audio', {
        ref: audio,
        src: uri,
        'data-testid': `${testID}.audio`,
        style: HIDDEN,
        onPlay: () => setPlaying(true),
        onPause: () => setPlaying(false),
        onEnded: () => setPlaying(false),
      })}
      <IconAction
        testID={testID}
        icon={playing ? 'pause' : 'play'}
        label={playing ? EN['voice.pause'] : EN['voice.play']}
        onPress={() => {
          const element = audio.current;
          if (element === null) return;
          if (element.paused) element.play().catch(() => undefined);
          else element.pause();
        }}
      />
    </View>
  );
}

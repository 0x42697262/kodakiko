import type { EntryMetadataV1 } from '@kodakiko/contract';
import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { z } from 'zod';
import { useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { IconAction } from '../../ui/IconAction';
import { FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { formatDuration } from '../capture/format-duration';
import { VoicePlayer } from './voice-player';

type Descriptor = z.infer<typeof EntryMetadataV1>['media'][number];

function VoiceTile({ entryId, media, position, onRemove }: { readonly entryId: string; readonly media: Descriptor; readonly position: number; readonly onRemove: (() => void) | null }) {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const [uri, setUri] = useState<string | null>(null);
  const [opening, setOpening] = useState(false);

  useEffect(() => {
    if (!opening) return undefined;
    let cancelled = false;
    let release: (() => Promise<void>) | null = null;
    services.originals
      .open(entryId, media.mediaId)
      .then((ref) => {
        if (ref === 'missing') return;
        release = ref.release;
        if (cancelled) ref.release().catch(() => undefined);
        else setUri(ref.uri);
      })
      .catch(() => setOpening(false));
    return () => {
      cancelled = true;
      release?.().catch(() => undefined);
    };
  }, [opening, entryId, media.mediaId, services.originals]);

  return (
    <View testID={`voice.tile.${position}`} accessibilityLabel={EN['voice.label'](position + 1)} style={[styles.tile, { backgroundColor: colors.surface }]}>
      {uri === null ? (
        <IconAction testID={`voice.play.${position}`} icon="play" label={EN['voice.play']} onPress={() => setOpening(true)} />
      ) : (
        <VoicePlayer uri={uri} testID={`voice.play.${position}`} autoPlay />
      )}
      <Text testID={`voice.duration.${position}`} style={[styles.duration, { color: colors.text }]}>
        {formatDuration(media.durationMs ?? 0)}
      </Text>
      {onRemove === null ? null : <IconAction testID={`voice.remove.${position}`} icon="trash" label={EN['voice.remove']} tonal={false} onPress={onRemove} />}
    </View>
  );
}

export function VoiceNotes({ entryId, media, onRemove }: { readonly entryId: string; readonly media: readonly Descriptor[]; readonly onRemove: ((mediaId: string) => void) | null }) {
  const voices = media.filter((item) => item.kind === 'audio');
  if (voices.length === 0) return null;
  return (
    <View testID="voice.list" style={styles.list}>
      {voices.map((item, position) => (
        <VoiceTile key={item.mediaId} entryId={entryId} media={item} position={position} onRemove={onRemove === null ? null : () => onRemove(item.mediaId)} />
      ))}
    </View>
  );
}

export function RecordingTile({ elapsedMs, onStop }: { readonly elapsedMs: number; readonly onStop: () => void }) {
  const { colors } = useTheme();
  return (
    <View testID="voice.recordingTile" style={[styles.tile, { backgroundColor: colors.surface }]}>
      <IconAction testID="voice.stop" icon="stop" label={EN['voice.stop']} onPress={onStop} />
      <Text testID="voice.elapsed" style={[styles.duration, { color: colors.danger }]}>
        {EN['voice.recording'](formatDuration(elapsedMs))}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  list: { gap: SPACING.sm },
  tile: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md, borderRadius: RADIUS.md, padding: SPACING.sm },
  duration: { flex: 1, fontSize: FONT_SIZE.body },
});

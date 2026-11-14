import { StyleSheet, View } from 'react-native';
import { EN } from '../../text/en';
import { IconAction } from '../../ui/IconAction';
import { SPACING } from '../../ui/theme-constants';

export function MuteButton({ testID, muted, onToggle }: { readonly testID: string; readonly muted: boolean; readonly onToggle: () => void }) {
  return (
    <View style={styles.mute}>
      <IconAction testID={testID} icon={muted ? 'volumeOff' : 'volumeOn'} label={muted ? EN['video.unmute'] : EN['video.mute']} onPress={onToggle} />
    </View>
  );
}

const styles = StyleSheet.create({ mute: { position: 'absolute', left: SPACING.sm, bottom: SPACING.sm } });

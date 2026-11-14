import { StyleSheet, Text, View } from 'react-native';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';

const SCRIM = 'rgba(0,0,0,0.5)';
const WHITE = '#FFFFFF';

export function CaptionOverlay({ caption, testID }: { readonly caption: string; readonly testID?: string }) {
  return (
    <View pointerEvents="none" style={styles.bar}>
      <Text testID={testID} numberOfLines={1} style={styles.text}>
        {caption}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  bar: { position: 'absolute', left: 0, right: 0, bottom: 0, backgroundColor: SCRIM, paddingHorizontal: SPACING.md, paddingVertical: SPACING.xs },
  text: { color: WHITE, fontSize: FONT_SIZE.body },
});

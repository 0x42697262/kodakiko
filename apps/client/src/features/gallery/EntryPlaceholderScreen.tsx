import { useRouter } from 'expo-router';
import { StyleSheet, Text } from 'react-native';
import { GALLERY_IDS } from '../../keys/gallery-ids';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { IconAction } from '../../ui/IconAction';
import { Screen } from '../../ui/Screen';
import { FONT_SIZE } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';

export function EntryPlaceholderScreen() {
  const router = useRouter();
  const { colors } = useTheme();
  return (
    <Screen testID={GALLERY_IDS.entryScreen}>
      <IconAction
        testID={testId('entryView.back')}
        label={EN['entryView.back']}
        onPress={() => router.back()}
        icon="back"
        tonal={false}
      />
      <Text testID={testId('entryView.title')} style={[styles.title, { color: colors.text }]}>
        {EN['entryView.title']}
      </Text>
    </Screen>
  );
}

const styles = StyleSheet.create({ title: { fontSize: FONT_SIZE.title, fontWeight: '600' } });

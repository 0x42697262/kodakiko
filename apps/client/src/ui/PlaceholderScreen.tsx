import { StyleSheet, Text } from 'react-native';
import { EN, type StaticTextKey } from '../text/en';
import { Screen } from './Screen';
import { FONT_SIZE } from './theme-constants';
import { useTheme } from './theme';

export function PlaceholderScreen({ titleKey }: { readonly titleKey: StaticTextKey }) {
  const { colors } = useTheme();
  return (
    <Screen testID={`${titleKey}.screen`}>
      <Text testID={titleKey} style={[styles.title, { color: colors.text }]}>
        {EN[titleKey]}
      </Text>
    </Screen>
  );
}

const styles = StyleSheet.create({ title: { fontSize: FONT_SIZE.title, fontWeight: '600' } });

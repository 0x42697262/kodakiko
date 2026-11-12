import { StyleSheet, Text } from 'react-native';
import { FONT_SIZE } from './theme-constants';
import { useTheme } from './theme';

type TypographyProps = {
  readonly testID?: string;
  readonly text: string;
  readonly selectable?: boolean;
};

export function Title({ testID, text }: TypographyProps) {
  const { colors } = useTheme();
  return (
    <Text testID={testID} accessibilityRole="header" style={[styles.title, { color: colors.text }]}>
      {text}
    </Text>
  );
}

export function Body({ testID, text, selectable = false }: TypographyProps) {
  const { colors } = useTheme();
  return (
    <Text
      testID={testID}
      selectable={selectable}
      style={[styles.body, { color: colors.textMuted }]}
    >
      {text}
    </Text>
  );
}

export function Hint({ testID, text }: TypographyProps) {
  const { colors } = useTheme();
  return (
    <Text testID={testID} style={[styles.hint, { color: colors.danger }]}>
      {text}
    </Text>
  );
}

const styles = StyleSheet.create({
  title: { fontSize: FONT_SIZE.title, fontWeight: '600' },
  body: { fontSize: FONT_SIZE.body },
  hint: { fontSize: FONT_SIZE.caption },
});

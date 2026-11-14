import type { ReactNode } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { useSystemInsets } from './system-insets';
import { SPACING } from './theme-constants';
import { useTheme } from './theme';

type ScreenProps = {
  readonly testID: string;
  readonly children: ReactNode;
  readonly scroll?: boolean;
};

export function Screen({ testID, children, scroll = false }: ScreenProps) {
  const theme = useTheme();
  const insets = useSystemInsets();
  return (
    <View
      testID={testID}
      style={[
        styles.root,
        { backgroundColor: theme.colors.background, paddingTop: insets.top, paddingBottom: insets.bottom, paddingLeft: insets.left, paddingRight: insets.right },
      ]}
    >
      {scroll ? (
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          {children}
        </ScrollView>
      ) : (
        <View style={[styles.content, styles.fill]}>{children}</View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  content: { flexGrow: 1, padding: SPACING.lg, gap: SPACING.md },
  fill: { flex: 1, minHeight: 0 },
});

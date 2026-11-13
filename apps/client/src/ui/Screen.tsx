import type { ReactNode } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { SPACING } from './theme-constants';
import { useTheme } from './theme';

type ScreenProps = {
  readonly testID: string;
  readonly children: ReactNode;
  readonly scroll?: boolean;
};

export function Screen({ testID, children, scroll = false }: ScreenProps) {
  const theme = useTheme();
  return (
    <SafeAreaView
      testID={testID}
      style={[styles.root, { backgroundColor: theme.colors.background }]}
    >
      {scroll ? (
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          {children}
        </ScrollView>
      ) : (
        <View style={[styles.content, styles.fill]}>{children}</View>
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  content: { flexGrow: 1, padding: SPACING.lg, gap: SPACING.md },
  fill: { flex: 1, minHeight: 0 },
});

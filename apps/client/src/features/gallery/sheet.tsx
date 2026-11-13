import type { ReactNode } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { OVERLAY_ELEVATION, OVERLAY_Z_INDEX, SCRIM_OPACITY } from '../../constants/gallery-constants';
import { RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';

type SheetProps = {
  readonly testID: string;
  readonly onClose: () => void;
  readonly children: ReactNode;
};

export function Sheet({ testID, onClose, children }: SheetProps) {
  const { colors } = useTheme();
  return (
    <View style={styles.overlay}>
      <Pressable
        accessibilityRole="none"
        style={[StyleSheet.absoluteFill, { backgroundColor: colors.text, opacity: SCRIM_OPACITY }]}
        onPress={onClose}
      />
      <View testID={testID} style={[styles.card, { backgroundColor: colors.background }]}>
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          {children}
        </ScrollView>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: {
    ...StyleSheet.absoluteFill,
    justifyContent: 'flex-end',
    zIndex: OVERLAY_Z_INDEX,
    elevation: OVERLAY_ELEVATION,
  },
  card: { maxHeight: '85%', borderTopLeftRadius: RADIUS.md, borderTopRightRadius: RADIUS.md },
  content: { padding: SPACING.lg, gap: SPACING.md },
});

import { useEffect, useMemo, type ReactNode } from 'react';
import { PanResponder, ScrollView, StyleSheet, useWindowDimensions, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withSpring, withTiming } from 'react-native-reanimated';
import { scheduleOnRN } from 'react-native-worklets';
import { OVERLAY_ELEVATION, OVERLAY_Z_INDEX, SCRIM_OPACITY } from '../../constants/gallery-constants';
import { RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { Touchable } from '../../ui/Touchable';
import { shouldCloseSheet } from './sheet-drag';

const SHEET_ANIMATION_MS = 220;
const DRAG_SLOP = 8;
const MS_PER_SECOND = 1_000;
const HANDLE_WIDTH = 32;
const HANDLE_HEIGHT = 4;
const HANDLE_OPACITY = 0.4;

type SheetProps = {
  readonly testID: string;
  readonly onClose: () => void;
  readonly children: ReactNode;
  readonly header?: ReactNode;
};

export function Sheet({ testID, onClose, children, header }: SheetProps) {
  const { colors } = useTheme();
  const window = useWindowDimensions();
  const height = useSharedValue(window.height);
  const offset = useSharedValue(window.height);

  useEffect(() => {
    offset.value = withTiming(0, { duration: SHEET_ANIMATION_MS });
  }, [offset]);

  const drag = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponder: (_event, gesture) => gesture.dy > DRAG_SLOP && Math.abs(gesture.dy) > Math.abs(gesture.dx),
        onPanResponderMove: (_event, gesture) => {
          offset.value = Math.max(0, gesture.dy);
        },
        onPanResponderRelease: (_event, gesture) => {
          if (shouldCloseSheet(gesture.dy, gesture.vy * MS_PER_SECOND, height.value)) {
            offset.value = withTiming(height.value, { duration: SHEET_ANIMATION_MS }, (finished) => {
              if (finished === true) scheduleOnRN(onClose);
            });
          } else {
            offset.value = withSpring(0);
          }
        },
        onPanResponderTerminationRequest: () => false,
        onPanResponderTerminate: () => {
          offset.value = withSpring(0);
        },
      }),
    [offset, height, onClose],
  );

  const slide = useAnimatedStyle(() => ({ transform: [{ translateY: offset.value }] }));

  return (
    <View style={styles.overlay}>
      <Touchable
        accessibilityRole="none"
        style={[StyleSheet.absoluteFill, { backgroundColor: colors.text, opacity: SCRIM_OPACITY }]}
        onPress={onClose}
      />
      <Animated.View
        testID={testID}
        onLayout={(event) => {
          height.value = event.nativeEvent.layout.height;
        }}
        style={[styles.card, { backgroundColor: colors.background }, slide]}
      >
        <View style={styles.dragArea} {...drag.panHandlers}>
          <View style={styles.grip}>
            <View style={[styles.handle, { backgroundColor: colors.textMuted }]} />
          </View>
          {header}
        </View>
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          {children}
        </ScrollView>
      </Animated.View>
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
  dragArea: { userSelect: 'none' },
  grip: { alignItems: 'center', paddingTop: SPACING.sm, paddingBottom: SPACING.xs },
  handle: { width: HANDLE_WIDTH, height: HANDLE_HEIGHT, borderRadius: HANDLE_HEIGHT, opacity: HANDLE_OPACITY },
  content: { padding: SPACING.lg, gap: SPACING.md },
});

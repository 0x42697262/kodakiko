import { useMemo } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import {
  SHUTTER_INNER_SIZE,
  SHUTTER_RING_WIDTH,
  SHUTTER_SIZE,
} from '../../constants/capture-constants';
import { TEST_IDS } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { useTheme } from '../../ui/theme';
import { useShutterGesture } from './use-shutter-gesture';

type ShutterButtonProps = {
  readonly recording: boolean;
  readonly disabled?: boolean;
  readonly onTap: () => void;
  readonly onHoldStart: () => void;
  readonly onHoldEnd: () => void;
};

export function ShutterButton({
  recording,
  disabled = false,
  onTap,
  onHoldStart,
  onHoldEnd,
}: ShutterButtonProps) {
  const { colors } = useTheme();
  const handlers = useMemo(() => ({ onTap, onHoldStart, onHoldEnd }), [onTap, onHoldStart, onHoldEnd]);
  const gesture = useShutterGesture(handlers);
  const accent = recording ? colors.danger : colors.text;
  return (
    <Pressable
      testID={TEST_IDS.captureShutter}
      accessibilityRole="button"
      accessibilityLabel={EN['capture.shutterLabel']}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPressIn={gesture.onPressIn}
      onPressOut={gesture.onPressOut}
      style={[styles.ring, { borderColor: accent }]}
    >
      <View style={[styles.inner, { backgroundColor: accent }]} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  ring: {
    width: SHUTTER_SIZE,
    height: SHUTTER_SIZE,
    borderRadius: SHUTTER_SIZE,
    borderWidth: SHUTTER_RING_WIDTH,
    alignItems: 'center',
    justifyContent: 'center',
  },
  inner: {
    width: SHUTTER_INNER_SIZE,
    height: SHUTTER_INNER_SIZE,
    borderRadius: SHUTTER_SIZE,
  },
});

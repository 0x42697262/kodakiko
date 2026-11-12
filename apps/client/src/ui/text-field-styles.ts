import { StyleSheet } from 'react-native';
import {
  BORDER_WIDTH,
  FONT_SIZE,
  MIN_TOUCH_TARGET,
  MULTILINE_MIN_HEIGHT,
  RADIUS,
  SPACING,
} from './theme-constants';

export const styles = StyleSheet.create({
  container: { gap: SPACING.xs },
  label: { fontSize: FONT_SIZE.caption },
  input: {
    minHeight: MIN_TOUCH_TARGET,
    paddingHorizontal: SPACING.md,
    borderRadius: RADIUS.sm,
    borderWidth: BORDER_WIDTH,
    fontSize: FONT_SIZE.body,
  },
  multiline: { minHeight: MULTILINE_MIN_HEIGHT },
  error: { fontSize: FONT_SIZE.caption },
});

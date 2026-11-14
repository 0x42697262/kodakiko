import { Platform, Pressable, type PressableProps, type PressableStateCallbackType, type StyleProp, type ViewStyle } from 'react-native';
import { useTheme } from './theme';

export const RIPPLE_ALPHA = 0.12;
export const WEB_PRESSED_OPACITY = 0.7;
const HEX_RADIX = 16;
const ALPHA_MAX = 255;
const HEX_PAIR = 2;

export function withAlpha(hex: string, alpha: number): string {
  return `${hex}${Math.round(alpha * ALPHA_MAX).toString(HEX_RADIX).toUpperCase().padStart(HEX_PAIR, '0')}`;
}

export type TouchableProps = Omit<PressableProps, 'style' | 'android_ripple'> & {
  readonly style?: StyleProp<ViewStyle> | ((state: PressableStateCallbackType) => StyleProp<ViewStyle>);
  readonly borderless?: boolean;
};

export function Touchable({ style, borderless = false, ...props }: TouchableProps) {
  const { colors } = useTheme();
  const android = Platform.OS === 'android';
  return (
    <Pressable
      {...props}
      android_ripple={android ? { color: withAlpha(colors.text, RIPPLE_ALPHA), borderless, foreground: true } : undefined}
      style={(state) => [
        typeof style === 'function' ? style(state) : style,
        !android && state.pressed ? { opacity: WEB_PRESSED_OPACITY } : null,
      ]}
    />
  );
}

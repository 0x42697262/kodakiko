import { useColorScheme } from 'react-native';
import { COLORS, type ColorTokens } from './theme-constants';

export type Theme = { readonly scheme: 'light' | 'dark'; readonly colors: ColorTokens };

export function useTheme(): Theme {
  const scheme = useColorScheme() === 'dark' ? 'dark' : 'light';
  return { scheme, colors: COLORS[scheme] };
}

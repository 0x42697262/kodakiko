import { ActivityIndicator } from 'react-native';
import { EN } from '../text/en';
import { useTheme } from './theme';

type SpinnerProps = { readonly testID: string; readonly color?: string };

export function Spinner({ testID, color }: SpinnerProps) {
  const theme = useTheme();
  return (
    <ActivityIndicator
      testID={testID}
      accessibilityLabel={EN['app.loading']}
      color={color ?? theme.colors.primary}
    />
  );
}

import { IconButton } from 'react-native-paper';
import { ICONS, type IconKey } from './icons';

export const ICON_ACTION_SIZE = 28;

export function IconAction({
  testID,
  icon,
  label,
  onPress,
  disabled = false,
  tonal = true,
  size = ICON_ACTION_SIZE,
}: {
  readonly testID: string;
  readonly icon: IconKey;
  readonly label: string;
  readonly onPress: () => void;
  readonly disabled?: boolean;
  readonly tonal?: boolean;
  readonly size?: number;
}) {
  return (
    <IconButton
      testID={testID}
      icon={ICONS[icon]}
      accessibilityLabel={label}
      {...(tonal ? { mode: 'contained-tonal' as const } : {})}
      size={size}
      disabled={disabled}
      onPress={onPress}
    />
  );
}

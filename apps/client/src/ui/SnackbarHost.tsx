import { useSyncExternalStore } from 'react';
import { Snackbar, Text, useTheme as usePaperTheme } from 'react-native-paper';
import { snackbar } from './snackbar-store';
import { useSystemInsets } from './system-insets';

export const SNACKBAR_MS = 4_000;
export const SNACKBAR_BOTTOM_OFFSET = 80;

export function SnackbarHost() {
  const message = useSyncExternalStore(snackbar.subscribe, snackbar.get, snackbar.get);
  const insets = useSystemInsets();
  const paper = usePaperTheme();
  if (message === null) return null;
  const { action } = message;
  return (
    <Snackbar
      key={message.id}
      visible
      duration={SNACKBAR_MS}
      onDismiss={() => snackbar.dismiss(message.id)}
      wrapperStyle={{ bottom: insets.bottom + SNACKBAR_BOTTOM_OFFSET }}
      {...(action === undefined
        ? {}
        : {
            action: {
              label: action.label,
              testID: action.testID,
              onPress: () => {
                snackbar.dismiss(message.id);
                action.onPress();
              },
            },
          })}
    >
      <Text testID={message.testID} style={{ color: paper.colors.inverseOnSurface }}>
        {message.text}
      </Text>
    </Snackbar>
  );
}

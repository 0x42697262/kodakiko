import '../src/config/zod-config';
import { Stack } from 'expo-router';
import { LogBox, StyleSheet } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AppContextProvider } from '../src/composition/app-context';
import { UI_TEST_MODE } from '../src/config/build-flags';
import { useTheme } from '../src/ui/theme';

if (UI_TEST_MODE) LogBox.ignoreAllLogs(true);

function ThemedStack() {
  const { colors } = useTheme();
  return (
    <Stack
      screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }}
    >
      <Stack.Screen name="capture" options={{ presentation: 'modal' }} />
      <Stack.Screen name="play/[entryId]/[mediaId]" options={{ presentation: 'fullScreenModal' }} />
    </Stack>
  );
}

export default function RootLayout() {
  return (
    <GestureHandlerRootView style={styles.root}>
      <SafeAreaProvider>
        <AppContextProvider>
          <ThemedStack />
        </AppContextProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({ root: { flex: 1 } });

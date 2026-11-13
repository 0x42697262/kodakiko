import '../src/config/zod-config';
import { Stack } from 'expo-router';
import { LogBox } from 'react-native';
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
    </Stack>
  );
}

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <AppContextProvider>
        <ThemedStack />
      </AppContextProvider>
    </SafeAreaProvider>
  );
}

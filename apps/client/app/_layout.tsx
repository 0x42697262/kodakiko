import '../src/config/zod-config';
import { Stack, usePathname } from 'expo-router';
import { LogBox, Platform, StyleSheet } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AppContextProvider } from '../src/composition/app-context';
import { UI_TEST_MODE } from '../src/config/build-flags';
import { ReturnToRecorder } from '../src/features/lock/ReturnToRecorder';
import { SettingsBinders } from '../src/features/settings/SettingsBinders';
import { PublicViewerProvider } from '../src/features/link-viewer/public-viewer-context';
import { LINK_PATH_PREFIX as PUBLIC_LINK_PREFIX } from '../src/services/links/link-url';
import { PaperProvider } from 'react-native-paper';
import { PaperIcon } from '../src/ui/icons';
import { paperTheme } from '../src/ui/paper-theme';
import { useTheme } from '../src/ui/theme';
import { SnackbarHost } from '../src/ui/SnackbarHost';
import { NoticeSnackbars } from '../src/features/entries/notice-snackbars';

if (UI_TEST_MODE) LogBox.ignoreAllLogs(true);

function ThemedStack() {
  const { colors } = useTheme();
  return (
    <Stack
      screenOptions={{
        headerShown: false,
        contentStyle: { backgroundColor: colors.background },
        ...(ANDROID ? { animation: 'slide_from_right' as const } : {}),
      }}
    >
      <Stack.Screen
        name="capture"
        options={{
          presentation: 'modal',
          ...(ANDROID ? { animation: 'slide_from_bottom' as const } : {}),
        }}
      />
      <Stack.Screen
        name="play/[entryId]/[mediaId]"
        options={{
          presentation: 'fullScreenModal',
          ...(ANDROID ? { animation: 'fade' as const } : {}),
        }}
      />
    </Stack>
  );
}

const PAPER_SETTINGS = { icon: PaperIcon };
const ANDROID = Platform.OS === 'android';

export default function RootLayout() {
  const publicLink = usePathname().startsWith(PUBLIC_LINK_PREFIX);
  const { scheme, colors } = useTheme();
  return (
    <GestureHandlerRootView style={styles.root}>
      <SafeAreaProvider>
        <PaperProvider theme={paperTheme(scheme, colors)} settings={PAPER_SETTINGS}>
          {publicLink ? (
            <PublicViewerProvider>
              <ThemedStack />
            </PublicViewerProvider>
          ) : (
            <AppContextProvider>
              <ThemedStack />
              <ReturnToRecorder />
              <SettingsBinders />
              <NoticeSnackbars />
            </AppContextProvider>
          )}
          <SnackbarHost />
        </PaperProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({ root: { flex: 1 } });

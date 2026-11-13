import { Tabs, useRouter } from 'expo-router';
import { useEffect } from 'react';
import { useAccountMode, useAccountState, useAppContext } from '../../src/composition/app-context';
import { ROUTE_BY_STATE } from '../../src/composition/route-by-state';
import { EN } from '../../src/text/en';
import { useTheme } from '../../src/ui/theme';
import { MaterialTabBar } from '../../src/features/navigation/material-tab-bar';

export default function TabsLayout() {
  const state = useAccountState();
  const mode = useAccountMode();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const router = useRouter();
  const unlocked = state.kind === 'unlocked';
  const sharingOn = services.config.get()?.sharingEnabled === true;
  const hidden: ReadonlySet<string> = new Set(mode === 'local' || !sharingOn ? ['shared'] : []);

  useEffect(() => {
    if (!unlocked) router.replace(ROUTE_BY_STATE[state.kind]);
  }, [unlocked, state.kind, router]);

  if (!unlocked) return null;
  return (
    <Tabs screenOptions={{ headerShown: false, sceneStyle: { backgroundColor: colors.background } }} tabBar={(props) => <MaterialTabBar {...props} hidden={hidden} />}>
      <Tabs.Screen name="index" options={{ title: EN['tabs.gallery'] }} />
      <Tabs.Screen name="calendar" options={{ title: EN['tabs.calendar'] }} />
      <Tabs.Screen name="shared" options={{ title: EN['tabs.shared'] }} />
      <Tabs.Screen name="more" options={{ title: EN['tabs.more'] }} />
    </Tabs>
  );
}

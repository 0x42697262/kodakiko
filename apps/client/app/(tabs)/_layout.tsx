import { Tabs, useRouter } from 'expo-router';
import { useEffect } from 'react';
import { useAccountState } from '../../src/composition/app-context';
import { ROUTE_BY_STATE } from '../../src/composition/route-by-state';
import { EN } from '../../src/text/en';

export default function TabsLayout() {
  const state = useAccountState();
  const router = useRouter();
  const unlocked = state.kind === 'unlocked';

  useEffect(() => {
    if (!unlocked) router.replace(ROUTE_BY_STATE[state.kind]);
  }, [unlocked, state.kind, router]);

  if (!unlocked) return null;
  return (
    <Tabs screenOptions={{ headerShown: false }}>
      <Tabs.Screen name="index" options={{ title: EN['home.title'] }} />
    </Tabs>
  );
}

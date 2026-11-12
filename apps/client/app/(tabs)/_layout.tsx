import { Redirect, Tabs } from 'expo-router';
import { useAccountState } from '../../src/composition/app-context';
import { APP_ROUTES } from '../../src/keys/routes';
import { EN } from '../../src/text/en';

export default function TabsLayout() {
  const state = useAccountState();
  if (state.kind !== 'unlocked') return <Redirect href={APP_ROUTES.root} />;
  return (
    <Tabs screenOptions={{ headerShown: false }}>
      <Tabs.Screen name="index" options={{ title: EN['home.title'] }} />
    </Tabs>
  );
}

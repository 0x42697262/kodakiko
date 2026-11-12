import { Tabs } from 'expo-router';
import { EN } from '../../src/text/en';

export default function TabsLayout() {
  return (
    <Tabs screenOptions={{ headerShown: false }}>
      <Tabs.Screen name="index" options={{ title: EN['home.title'] }} />
    </Tabs>
  );
}

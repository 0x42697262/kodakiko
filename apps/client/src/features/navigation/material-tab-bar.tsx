import type { Tabs } from 'expo-router';
import type { ComponentProps } from 'react';
import { BottomNavigation } from 'react-native-paper';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { useBarsHidden } from '../../ui/bars-store';
import { useSystemInsets } from '../../ui/system-insets';
import { Icon, type IconKey } from '../../ui/icons';

type TabBarProps = Parameters<NonNullable<ComponentProps<typeof Tabs>['tabBar']>>[0];

const TAB_ICON_SIZE = 24;

const TABS: Readonly<Record<string, { readonly icon: IconKey; readonly title: string; readonly testID: string }>> = {
  index: { icon: 'gallery', title: EN['tabs.gallery'], testID: testId('tabs.gallery') },
  calendar: { icon: 'calendar', title: EN['tabs.calendar'], testID: testId('tabs.calendar') },
  shared: { icon: 'shared', title: EN['tabs.shared'], testID: testId('tabs.shared') },
  more: { icon: 'more', title: EN['tabs.more'], testID: 'tabs.more' },
};

export function MaterialTabBar({ state, navigateToTab, insets, hidden }: TabBarProps & { readonly hidden: ReadonlySet<string> }) {
  const barsHidden = useBarsHidden();
  const minimum = useSystemInsets();
  const routes = state.routes.filter((route) => !hidden.has(route.name));
  if (barsHidden) return null;
  const active = state.routes[state.index];
  const index = Math.max(0, routes.findIndex((route) => route.key === active?.key));
  return (
    <BottomNavigation.Bar
      navigationState={{ index, routes: routes.map((route) => ({ key: route.key, title: route.name })) }}
      safeAreaInsets={{ ...insets, bottom: Math.max(insets.bottom, minimum.bottom) }}
      renderIcon={({ route, color }) => {
        const tab = TABS[route.title ?? ''];
        return tab === undefined ? null : <Icon name={tab.icon} size={TAB_ICON_SIZE} color={color} />;
      }}
      getLabelText={({ route }) => TABS[route.title ?? '']?.title}
      getTestID={({ route }) => TABS[route.title ?? '']?.testID}
      onTabPress={({ route }) => navigateToTab(route.key)}
    />
  );
}

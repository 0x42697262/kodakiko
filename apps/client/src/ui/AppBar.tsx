import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Appbar, Menu, useTheme } from 'react-native-paper';
import { EN } from '../text/en';
import { ICONS, type IconKey } from './icons';

export type AppBarAction = {
  readonly key: string;
  readonly icon: IconKey;
  readonly label: string;
  readonly testID: string;
  readonly onPress: () => void;
  readonly active?: boolean;
};

export type AppBarMenuItem = {
  readonly key: string;
  readonly label: string;
  readonly testID: string;
  readonly onPress: () => void;
};

export function AppBar({
  title,
  subtitle,
  backTestID,
  onBack,
  actions = [],
  menu = [],
  menuTestID,
}: {
  readonly title: string;
  readonly subtitle?: string;
  readonly backTestID?: string;
  readonly onBack?: () => void;
  readonly actions?: readonly AppBarAction[];
  readonly menu?: readonly AppBarMenuItem[];
  readonly menuTestID?: string;
}) {
  const router = useRouter();
  const [menuOpen, setMenuOpen] = useState(false);
  const theme = useTheme();
  return (
    <Appbar.Header statusBarHeight={0}>
      {backTestID === undefined ? null : (
        <Appbar.Action
          testID={backTestID}
          icon={ICONS.back}
          accessibilityLabel={EN['settings.back']}
          onPress={onBack ?? (() => router.back())}
        />
      )}
      <Appbar.Content title={title} {...(subtitle === undefined ? {} : { subtitle })} />
      {actions.map((action) => (
        <Appbar.Action
          key={action.key}
          testID={action.testID}
          icon={ICONS[action.icon]}
          accessibilityLabel={action.label}
          {...(action.active === true ? { color: theme.colors.primary } : {})}
          onPress={action.onPress}
        />
      ))}
      {menu.length === 0 ? null : (
        <Menu
          visible={menuOpen}
          onDismiss={() => setMenuOpen(false)}
          anchor={
            <Appbar.Action
              {...(menuTestID === undefined ? {} : { testID: menuTestID })}
              icon={ICONS.overflow}
              accessibilityLabel={EN['gallery.more']}
              onPress={() => setMenuOpen(true)}
            />
          }
        >
          {menu.map((item) => (
            <Menu.Item
              key={item.key}
              testID={item.testID}
              title={item.label}
              onPress={() => {
                setMenuOpen(false);
                item.onPress();
              }}
            />
          ))}
        </Menu>
      )}
    </Appbar.Header>
  );
}

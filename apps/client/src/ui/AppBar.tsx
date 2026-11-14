import { useRouter } from '../navigation/use-router';
import { useState, type ReactNode } from 'react';
import { Pressable, StyleSheet, Text } from 'react-native';
import { Appbar, Portal, Surface, useTheme } from 'react-native-paper';
import { useSystemInsets } from './system-insets';
import { FONT_SIZE, MIN_TOUCH_TARGET, SPACING } from './theme-constants';
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
  titleNode,
  backIcon = 'back',
  backLabel,
  subtitle,
  backTestID,
  onBack,
  actions = [],
  menu = [],
  menuTestID,
}: {
  readonly title: string;
  readonly titleNode?: ReactNode;
  readonly backIcon?: IconKey;
  readonly backLabel?: string;
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
  const insets = useSystemInsets();
  return (
    <Appbar.Header statusBarHeight={0}>
      {backTestID === undefined ? null : (
        <Appbar.Action
          testID={backTestID}
          icon={ICONS[backIcon]}
          accessibilityLabel={backLabel ?? EN['settings.back']}
          onPress={onBack ?? (() => router.back())}
        />
      )}
      <Appbar.Content title={titleNode ?? title} accessibilityLabel={title} {...(subtitle === undefined ? {} : { subtitle })} />
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
        <Appbar.Action
          {...(menuTestID === undefined ? {} : { testID: menuTestID })}
          icon={ICONS.overflow}
          accessibilityLabel={EN['gallery.more']}
          onPress={() => setMenuOpen(true)}
        />
      )}
      {menuOpen ? (
        <Portal>
          <Pressable style={StyleSheet.absoluteFill} accessibilityLabel={EN['settings.cancel']} onPress={() => setMenuOpen(false)} />
          <Surface style={[styles.menu, { top: insets.top + MENU_TOP, backgroundColor: theme.colors.elevation.level2 }]} elevation={MENU_ELEVATION}>
            {menu.map((item) => (
              <Pressable
                key={item.key}
                testID={item.testID}
                role="menuitem"
                style={({ pressed }) => [styles.menuItem, pressed ? { backgroundColor: theme.colors.surfaceVariant } : null]}
                onPress={() => {
                  setMenuOpen(false);
                  item.onPress();
                }}
              >
                <Text style={[styles.menuText, { color: theme.colors.onSurface }]}>{item.label}</Text>
              </Pressable>
            ))}
          </Surface>
        </Portal>
      ) : null}
    </Appbar.Header>
  );
}

const MENU_TOP = 48;
const MENU_ELEVATION = 3;
const MENU_MIN_WIDTH = 200;

const styles = StyleSheet.create({
  menu: { position: 'absolute', right: SPACING.sm, minWidth: MENU_MIN_WIDTH, borderRadius: SPACING.xs, paddingVertical: SPACING.xs },
  menuItem: { minHeight: MIN_TOUCH_TARGET, justifyContent: 'center', paddingHorizontal: SPACING.lg },
  menuText: { fontSize: FONT_SIZE.body },
});

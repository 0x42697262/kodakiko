import { useRouter, type Href } from 'expo-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { Text } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { useTheme } from '../../ui/theme';
import { Sheet } from '../gallery/sheet';
import { SettingsPage, SettingsRow } from './settings-ui';

type Row = { readonly key: string; readonly label: string; readonly route: string; readonly shown: boolean };

export function SettingsScreen() {
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const sync = useSyncExternalStore(services.sync.subscribe, services.sync.status);
  const [admin, setAdmin] = useState(false);
  const [confirmingSignOut, setConfirmingSignOut] = useState(false);
  useEffect(() => {
    services.account.isAdmin().then(setAdmin).catch(() => setAdmin(false));
  }, [services.account]);
  const links = services.config.get()?.publicLinksEnabled === true;
  const rows: readonly Row[] = [
    { key: 'account', label: EN['settings.rowAccount'], route: '/settings/account', shown: true },
    { key: 'security', label: EN['settings.rowSecurity'], route: '/settings/security', shown: true },
    { key: 'storage', label: EN['settings.rowStorage'], route: '/settings/storage', shown: true },
    { key: 'sync', label: EN['settings.rowSync'], route: '/settings/sync', shown: true },
    { key: 'links', label: EN['settings.rowLinks'], route: '/settings/links', shown: links },
    { key: 'export', label: EN['settings.rowExport'], route: '/settings/export', shown: true },
    { key: 'admin', label: EN['settings.rowAdmin'], route: '/settings/admin', shown: admin },
    { key: 'about', label: EN['settings.rowAbout'], route: '/settings/about', shown: true },
  ];
  const signOut = () => {
    setConfirmingSignOut(false);
    services.auth.signOut().catch(() => undefined);
  };
  return (
    <SettingsPage testID="settings.screen" title={EN['settings.title']}>
      {rows
        .filter((row) => row.shown)
        .map((row) => (
          <SettingsRow key={row.key} testID={`settings.row.${row.key}`} label={row.label} onPress={() => router.push(row.route as Href)} />
        ))}
      <SettingsRow testID="settings.row.lockNow" label={EN['settings.lockNow']} onPress={() => services.lock.lockNow().catch(() => undefined)} />
      <SettingsRow testID="settings.row.signOut" label={EN['settings.signOut']} onPress={() => (sync.queued > 0 ? setConfirmingSignOut(true) : signOut())} />
      {confirmingSignOut ? (
        <Sheet testID="settings.signOutSheet" onClose={() => setConfirmingSignOut(false)}>
          <Text style={{ color: colors.text }}>{EN['settings.signOutConfirm']}</Text>
          <Button testID="settings.signOutConfirm" label={EN['settings.signOut']} variant="danger" onPress={signOut} />
          <Button testID="settings.cancel" label={EN['settings.cancel']} variant="secondary" onPress={() => setConfirmingSignOut(false)} />
        </Sheet>
      ) : null}
    </SettingsPage>
  );
}

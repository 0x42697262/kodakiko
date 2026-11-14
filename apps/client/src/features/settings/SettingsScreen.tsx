import { type Href } from 'expo-router';
import { useRouter } from '../../navigation/use-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { Text, View } from 'react-native';
import { useAccountMode, useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { PreferenceList } from '../../ui/preferences/PreferenceList';
import type { PreferenceItem } from '../../ui/preferences/preference-types';
import { TextField } from '../../ui/TextField';
import { useTheme } from '../../ui/theme';
import { SPACING } from '../../ui/theme-constants';
import { Sheet } from '../gallery/sheet';
import { searchSettings } from './settings-search';
import { SettingsPage } from './settings-ui';

export function SettingsScreen() {
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const local = useAccountMode() === 'local';
  const sync = useSyncExternalStore(services.sync.subscribe, services.sync.status);
  const [admin, setAdmin] = useState(false);
  const [confirmingSignOut, setConfirmingSignOut] = useState(false);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');
  useEffect(() => {
    services.account.isAdmin().then(setAdmin).catch(() => setAdmin(false));
  }, [services.account]);
  const links = !local && services.config.get()?.publicLinksEnabled === true;
  const go = (route: string) => () => router.push(route as Href);
  const signOut = () => {
    setConfirmingSignOut(false);
    services.auth.signOut().catch(() => undefined);
  };

  const rows: PreferenceItem[] = [
    { kind: 'text', key: 'appearance', testID: 'settings.row.appearance', icon: 'appearance', title: EN['prefs.appearance'], summary: EN['prefs.appearanceSummary'], onPress: go('/settings/appearance') },
    { kind: 'text', key: 'gallery', testID: 'settings.row.gallery', icon: 'gallery', title: EN['prefs.gallery'], summary: EN['prefs.gallerySummary'], onPress: go('/settings/gallery') },
    { kind: 'text', key: 'tags', testID: 'settings.row.tags', icon: 'tags', title: EN['more.tags'], summary: EN['tags.settingsSummary'], onPress: go('/tags') },
    { kind: 'text', key: 'capture', testID: 'settings.row.capture', icon: 'camera', title: EN['prefs.capture'], summary: EN['prefs.captureSummary'], onPress: go('/settings/capture') },
    { kind: 'text', key: 'sync', testID: 'settings.row.sync', icon: 'sync', title: EN['settings.rowSync'], summary: EN['prefs.syncSummary'], onPress: go('/settings/sync') },
    { kind: 'text', key: 'storage', testID: 'settings.row.storage', icon: 'storage', title: EN['prefs.dataStorage'], summary: EN['prefs.dataStorageSummary'], onPress: go('/settings/storage') },
    { kind: 'text', key: 'security', testID: 'settings.row.security', icon: 'security', title: EN['prefs.security'], summary: EN['prefs.securitySummary'], onPress: go('/settings/security') },
    { kind: 'text', key: 'account', testID: 'settings.row.account', icon: 'account', title: EN['settings.rowAccount'], summary: EN['prefs.accountSummary'], onPress: go('/settings/account') },
  ];
  if (links) rows.push({ kind: 'text', key: 'links', testID: 'settings.row.links', icon: 'link', title: EN['settings.rowLinks'], summary: EN['prefs.linksSummary'], onPress: go('/settings/links') });
  if (admin && !local) rows.push({ kind: 'text', key: 'admin', testID: 'settings.row.admin', icon: 'admin', title: EN['settings.rowAdmin'], summary: EN['prefs.adminSummary'], onPress: go('/settings/admin') });
  rows.push(
    { kind: 'text', key: 'advanced', testID: 'settings.row.advanced', icon: 'code', title: EN['advanced.title'], summary: EN['advanced.summary'], onPress: go('/settings/advanced') },
    { kind: 'text', key: 'about', testID: 'settings.row.about', icon: 'info', title: EN['settings.rowAbout'], summary: EN['prefs.aboutSummary'], onPress: go('/settings/about') },
    { kind: 'text', key: 'lockNow', testID: 'settings.row.lockNow', icon: 'lock', title: EN['settings.lockNow'], onPress: () => services.lock.lockNow().catch(() => undefined) },
  );
  if (!local) {
    rows.push({ kind: 'text', key: 'signOut', testID: 'settings.row.signOut', icon: 'back', title: EN['settings.signOut'], danger: true, onPress: () => (sync.queued > 0 ? setConfirmingSignOut(true) : signOut()) });
  }

  const results: PreferenceItem[] = searchSettings(query).map((result, position) => ({
    kind: 'text',
    key: `${result.route}-${result.title}-${position}`,
    testID: `settings.result.${position}`,
    title: result.title,
    summary: result.section,
    onPress: go(result.route),
  }));

  return (
    <SettingsPage
      testID="settings.screen"
      title={EN['settings.title']}
      padded={false}
      actions={[{ key: 'search', icon: searching ? 'close' : 'search', label: EN['prefs.search'], testID: 'settings.search', onPress: () => { setSearching(!searching); setQuery(''); } }]}
    >
      {searching ? (
        <View>
          <View style={{ padding: SPACING.lg }}>
            <TextField testID="settings.searchField" label={EN['prefs.search']} value={query} onChangeText={setQuery} autoCapitalize="none" />
          </View>
          {query.trim() !== '' && results.length === 0 ? <Text style={{ color: colors.textMuted, paddingHorizontal: SPACING.lg }}>{EN['prefs.noResults']}</Text> : null}
          <PreferenceList items={results} />
        </View>
      ) : (
        <PreferenceList items={rows} />
      )}
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

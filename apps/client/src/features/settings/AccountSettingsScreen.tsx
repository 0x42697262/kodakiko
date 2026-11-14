import { useFocusEffect, type Href } from 'expo-router';
import { useRouter } from '../../navigation/use-router';
import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { Platform, StyleSheet, Text } from 'react-native';
import { useAccountMode, useAccountState, useAppContext } from '../../composition/app-context';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import { listTestId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Body } from '../../ui/Typography';
import { FONT_FAMILY_MONO, FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { formatBytes } from '../format/format-bytes';
import { Sheet } from '../gallery/sheet';
import { SHARING_IDS } from '../sharing/sharing-ids';
import { LabeledValue, SettingsPage, SettingsRow } from './settings-ui';

const TIME_CHARS = 16;

export function AccountSettingsScreen() {
  const router = useRouter();
  const { services, serverUrl } = useAppContext();
  const { colors } = useTheme();
  const state = useAccountState();
  const local = useAccountMode() === 'local';
  const user = useSyncExternalStore(services.account.user.subscribe, services.account.user.get);
  const sync = useSyncExternalStore(services.sync.subscribe, services.sync.status);
  const [host, setHost] = useState('');
  const [refreshedAt, setRefreshedAt] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const [changingServer, setChangingServer] = useState(false);
  const [revealing, setRevealing] = useState(false);
  const [password, setPassword] = useState('');
  const [words, setWords] = useState<readonly string[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useFocusEffect(
    useCallback(() => {
      if (local) return () => setWords(null);
      services.account
        .refreshMe()
        .then((fresh) => {
          setStale(fresh === null);
          if (fresh !== null) setRefreshedAt(new Date().toISOString());
        })
        .catch(() => setStale(true));
      return () => {
        setWords(null);
        setPassword('');
      };
    }, [services.account, local]),
  );

  useEffect(() => {
    serverUrl
      .current()
      .then((url) => setHost(url === null ? '' : new URL(url).host))
      .catch(() => undefined);
  }, [serverUrl]);

  useEffect(() => {
    if (state.kind !== 'unlocked') setWords(null);
  }, [state.kind]);

  const username = state.kind === 'unlocked' ? state.username : '';
  const quota = user?.quotaBytes ?? (services.config.get() ?? CONFIG_FALLBACK).defaultQuotaBytes;

  async function reveal(): Promise<void> {
    setError(null);
    try {
      setWords(await services.auth.revealRecoveryWords(password));
      setPassword('');
    } catch {
      setError(EN['settings.passwordWrong']);
    }
  }

  return (
    <SettingsPage testID="settings.accountScreen" title={EN['settings.accountTitle']}>
      <LabeledValue testID="settings.username" label={EN['settings.username']} value={username} />
      {local ? (
        <>
          <Body testID="settings.localOnly" text={EN['settings.localOnly']} />
          <SettingsRow testID="settings.row.connect" label={EN['settings.connect']} onPress={() => router.push('/settings/connect' as Href)} />
        </>
      ) : (
        <LabeledValue testID="settings.server" label={EN['settings.server']} value={host} />
      )}
      {user === null || local ? null : (
        <Body
          testID="settings.storageUse"
          text={`${EN['settings.storageUse'](formatBytes(user.usedBytes), formatBytes(quota))}${stale && refreshedAt !== null ? ` (${EN['settings.asOf'](refreshedAt.slice(0, TIME_CHARS).replace('T', ' '))})` : ''}`}
        />
      )}
      {Platform.OS === 'android' && !local ? (
        <Button testID="settings.changeServer" label={EN['settings.changeServer']} variant="secondary" onPress={() => setChangingServer(true)} />
      ) : null}
      {local ? null : (
        <>
          <Text style={{ color: colors.textMuted }}>{EN['settings.securityCode']}</Text>
          <Text testID={SHARING_IDS.securityCodeOwn} selectable style={[styles.code, { color: colors.text }]}>
            {services.sharing.myFingerprint()}
          </Text>
          <Body text={EN['settings.securityCodeOthers']} />
        </>
      )}
      <SettingsRow testID="settings.row.server" label={EN['server.title']} onPress={() => router.push('/settings/server' as Href)} />
      <SettingsRow testID="settings.row.password" label={EN['settings.changePassword']} onPress={() => router.push('/settings/password' as Href)} />
      <SettingsRow testID="settings.row.recovery" label={EN['settings.showRecovery']} onPress={() => setRevealing(true)} />
      <SettingsRow
        testID="settings.row.deleteAccount"
        label={local ? EN['settings.erase'] : EN['settings.deleteAccount']}
        onPress={() => router.push('/settings/delete-account' as Href)}
      />
      {changingServer ? (
        <Sheet testID="settings.changeServerSheet" onClose={() => setChangingServer(false)}>
          <Text style={{ color: colors.text }}>{EN['settings.changeServerTitle']}</Text>
          {sync.queued > 0 ? <Text style={{ color: colors.danger }}>{EN['settings.changeServerUnsynced'](sync.queued)}</Text> : null}
          <Button
            testID="settings.changeServerConfirm"
            label={EN['settings.changeServerAction']}
            variant="danger"
            onPress={() => {
              setChangingServer(false);
              services.auth
                .changeServer()
                .then(() => router.replace('/welcome'))
                .catch(() => undefined);
            }}
          />
          <Button testID="settings.cancel" label={EN['settings.cancel']} variant="secondary" onPress={() => setChangingServer(false)} />
        </Sheet>
      ) : null}
      {revealing ? (
        <Sheet
          testID="settings.recoverySheet"
          onClose={() => {
            setRevealing(false);
            setWords(null);
          }}
        >
          {words === null ? (
            <>
              <Body text={EN['settings.recoveryPrompt']} />
              <TextField testID="settings.recoveryPassword" label={EN['settings.deletePassword']} value={password} onChangeText={setPassword} secureTextEntry onSubmit={() => reveal().catch(() => undefined)} />
              {error === null ? null : <Banner testID="settings.error" kind="error" message={error} />}
              <Button testID="settings.recoveryShow" label={EN['settings.recoveryShow']} onPress={() => reveal().catch(() => undefined)} />
            </>
          ) : (
            <>
              <Text testID="settings.recoveryWords" selectable style={[styles.code, { color: colors.text }]}>
                {words.map((word, index) => `${index + 1}. ${word}`).join('\n')}
              </Text>
              {words.map((word, index) => (
                <Text key={word + String(index)} testID={listTestId('settings.recoveryWord', index + 1)} style={styles.hidden}>
                  {word}
                </Text>
              ))}
              <Button
                testID="settings.recoveryDone"
                label={EN['settings.recoveryDone']}
                onPress={() => {
                  setWords(null);
                  setRevealing(false);
                }}
              />
            </>
          )}
        </Sheet>
      ) : null}
    </SettingsPage>
  );
}

const styles = StyleSheet.create({
  code: { fontFamily: FONT_FAMILY_MONO, fontSize: FONT_SIZE.body, lineHeight: FONT_SIZE.body + SPACING.sm },
  hidden: { display: 'none' },
});

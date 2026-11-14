import { useRouter } from '../../navigation/use-router';
import { useState } from 'react';
import { useAccountMode, useAccountState, useAppContext } from '../../composition/app-context';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Body } from '../../ui/Typography';
import { deletionAllowed } from './delete-account-model';
import { SettingsPage } from './settings-ui';

export function DeleteAccountScreen() {
  const router = useRouter();
  const { services } = useAppContext();
  const state = useAccountState();
  const local = useAccountMode() === 'local';
  const username = state.kind === 'unlocked' ? state.username : '';
  const graceDays = (services.config.get() ?? CONFIG_FALLBACK).accountDeletionGraceDays;
  const [typed, setTyped] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove(): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      if (!(await services.auth.verifyPassword(password))) {
        setError(EN['settings.passwordWrong']);
        return;
      }
      if (local) {
        await services.auth.eraseLocal();
        router.replace('/welcome');
        return;
      }
      await services.auth.requestAccountDeletion();
      router.replace('/sign-in');
    } catch {
      setError(EN['admin.failed']);
    } finally {
      setBusy(false);
    }
  }

  return (
    <SettingsPage testID="settings.deleteScreen" title={local ? EN['settings.erase'] : EN['settings.deleteTitle']}>
      <Body text={local ? EN['settings.eraseText'] : EN['settings.deleteText'](graceDays)} />
      <TextField testID="settings.deleteUsername" label={EN['settings.deleteTypeUsername']} value={typed} onChangeText={setTyped} autoCapitalize="none" />
      <TextField testID="settings.deletePassword" label={EN['settings.deletePassword']} value={password} onChangeText={setPassword} secureTextEntry />
      {error === null ? null : <Banner testID="settings.error" kind="error" message={error} />}
      <Button
        testID="settings.deleteConfirm"
        label={local ? EN['settings.eraseAction'] : EN['settings.deleteAction']}
        variant="danger"
        busy={busy}
        disabled={!deletionAllowed(typed, username, password)}
        onPress={() => remove().catch(() => undefined)}
      />
    </SettingsPage>
  );
}

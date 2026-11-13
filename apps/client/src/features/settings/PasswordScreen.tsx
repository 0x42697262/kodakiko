import { useState } from 'react';
import { useAccountState, useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Body, Hint } from '../../ui/Typography';
import { AuthError } from '../../services/auth-errors';
import { PASSWORD_RULE_KEYS } from '../auth/error-text';
import { passwordHints } from '../auth/password-hints';
import { SettingsPage } from './settings-ui';

export function PasswordScreen() {
  const { services } = useAppContext();
  const state = useAccountState();
  const username = state.kind === 'unlocked' ? state.username : '';
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ readonly kind: 'info' | 'error'; readonly text: string } | null>(null);
  const hints = passwordHints(next, username, next.length > 0);
  const mismatch = confirm.length > 0 && confirm !== next;

  async function submit(): Promise<void> {
    if (hints.length > 0 || confirm !== next || current.length === 0) return;
    setBusy(true);
    setMessage(null);
    try {
      await services.auth.changePassword(current, next);
      setCurrent('');
      setNext('');
      setConfirm('');
      setMessage({ kind: 'info', text: EN['settings.passwordChanged'] });
    } catch (error) {
      const wrong = error instanceof AuthError && (error.reason === 'wrongPassword' || error.reason === 'invalidCredentials');
      setMessage({ kind: 'error', text: wrong ? EN['settings.passwordWrong'] : EN['admin.failed'] });
    } finally {
      setBusy(false);
    }
  }

  return (
    <SettingsPage testID="settings.passwordScreen" title={EN['settings.passwordTitle']}>
      <TextField testID="settings.currentPassword" label={EN['settings.currentPassword']} value={current} onChangeText={setCurrent} secureTextEntry />
      <TextField testID="settings.newPassword" label={EN['settings.newPassword']} value={next} onChangeText={setNext} secureTextEntry />
      {hints.map((rule) => (
        <Hint key={rule} testID={PASSWORD_RULE_KEYS[rule]} text={EN[PASSWORD_RULE_KEYS[rule]]} />
      ))}
      <TextField
        testID="settings.confirmPassword"
        label={EN['settings.confirmPassword']}
        value={confirm}
        onChangeText={setConfirm}
        secureTextEntry
        errorText={mismatch ? EN['settings.passwordMismatch'] : null}
        errorTestID="settings.passwordMismatch"
        onSubmit={() => submit().catch(() => undefined)}
      />
      {busy ? <Body testID="settings.passwordSecuring" text={EN['settings.passwordSecuring']} /> : null}
      {message === null ? null : <Banner testID="settings.passwordResult" kind={message.kind} message={message.text} />}
      <Button
        testID="settings.passwordSubmit"
        label={EN['settings.passwordSubmit']}
        busy={busy}
        disabled={hints.length > 0 || next.length === 0 || confirm !== next || current.length === 0}
        onPress={() => submit().catch(() => undefined)}
      />
    </SettingsPage>
  );
}

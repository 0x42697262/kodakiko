import { useRouter } from '../../navigation/use-router';
import { useState } from 'react';
import { useAppContext } from '../../composition/app-context';
import { APP_ROUTES } from '../../keys/routes';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Hint, Title } from '../../ui/Typography';
import { SwitchRow } from '../settings/settings-ui';
import { PASSWORD_RULE_KEYS, errorBanner } from './error-text';
import { passwordHints } from './password-hints';
import { useSubmit } from './use-submit';

export function LocalSetupScreen() {
  const { services } = useAppContext();
  const router = useRouter();
  const [understood, setUnderstood] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [touched, setTouched] = useState(false);
  const { busy, error, submit } = useSubmit();
  const hints = passwordHints(password, username, touched);
  const mismatch = touched && confirm !== password;
  const banner = error === null ? null : errorBanner(error);

  const create = () => {
    setTouched(true);
    if (!understood || passwordHints(password, username, true).length > 0 || confirm !== password) return;
    submit(async () => {
      await services.auth.setUpLocal({ username, password });
      router.replace(APP_ROUTES.root);
    });
  };

  return (
    <Screen testID="localSetup.screen" scroll>
      <Title testID="localSetup.title" text={EN['localSetup.title']} />
      <Banner testID="localSetup.warning" kind="warning" message={EN['localSetup.warning']} />
      <SwitchRow testID="localSetup.understand" label={EN['localSetup.understand']} value={understood} onChange={setUnderstood} />
      {banner === null ? null : <Banner testID={banner.testID} kind="error" message={banner.message} />}
      <TextField testID="localSetup.username" label={EN['signUp.username']} value={username} onChangeText={setUsername} autoCapitalize="none" />
      <TextField testID="localSetup.password" label={EN['signUp.password']} value={password} onChangeText={setPassword} secureTextEntry />
      {hints.map((rule) => (
        <Hint key={rule} testID={PASSWORD_RULE_KEYS[rule]} text={EN[PASSWORD_RULE_KEYS[rule]]} />
      ))}
      <TextField
        testID="localSetup.confirm"
        label={EN['signUp.confirm']}
        value={confirm}
        onChangeText={setConfirm}
        secureTextEntry
        errorText={mismatch ? EN['settings.passwordMismatch'] : null}
        onSubmit={create}
      />
      <Button testID="localSetup.create" label={EN['localSetup.create']} busy={busy} disabled={!understood} onPress={create} />
    </Screen>
  );
}

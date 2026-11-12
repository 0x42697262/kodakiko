import { useRouter } from 'expo-router';
import { useState } from 'react';
import { useAppContext } from '../../composition/app-context';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import { APP_ROUTES } from '../../keys/routes';
import { AuthError } from '../../services/auth-errors';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Hint, Title } from '../../ui/Typography';
import { PASSWORD_RULE_KEYS, errorBanner } from './error-text';
import { extractInviteToken } from './invite-input';
import { passwordHints } from './password-hints';
import { useSubmit } from './use-submit';

export function SignUpScreen() {
  const { services, inviteToken } = useAppContext();
  const router = useRouter();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [inviteInput, setInviteInput] = useState('');
  const [touched, setTouched] = useState(false);
  const { busy, error, submit } = useSubmit();
  const knownToken = inviteToken.get();
  const signupMode = (services.config.get() ?? CONFIG_FALLBACK).signupMode;
  const asksForInvite = knownToken === null && signupMode === 'invite';
  const hints = passwordHints(password, username, touched);
  const mismatch = touched && confirm !== password;
  const banner = error === null ? null : errorBanner(error);

  const create = () => {
    setTouched(true);
    if (passwordHints(password, username, true).length > 0 || confirm !== password) return;
    submit(async () => {
      const token = knownToken ?? (asksForInvite ? extractInviteToken(inviteInput) : null);
      if (asksForInvite && token === null) throw new AuthError('inviteInvalid');
      await services.auth.signUp({ username, password, inviteToken: token, keepSignedIn: false });
      router.replace(APP_ROUTES.root);
    });
  };

  return (
    <Screen testID="signUp.screen" scroll>
      <Title testID="signUp.title" text={EN['signUp.title']} />
      {banner === null ? null : (
        <Banner testID={banner.testID} kind="error" message={banner.message} />
      )}
      {asksForInvite ? (
        <TextField
          testID="signUp.invite"
          label={EN['signUp.invite']}
          value={inviteInput}
          onChangeText={setInviteInput}
          onSubmit={create}
        />
      ) : null}
      <TextField
        testID="signUp.username"
        label={EN['signUp.username']}
        value={username}
        onChangeText={setUsername}
        onSubmit={create}
      />
      <TextField
        testID="signUp.password"
        label={EN['signUp.password']}
        value={password}
        onChangeText={(value) => {
          setPassword(value);
          setTouched(true);
        }}
        secureTextEntry
        onSubmit={create}
      />
      {hints.map((rule) => (
        <Hint key={rule} testID={PASSWORD_RULE_KEYS[rule]} text={EN[PASSWORD_RULE_KEYS[rule]]} />
      ))}
      <TextField
        testID="signUp.confirm"
        label={EN['signUp.confirm']}
        value={confirm}
        onChangeText={setConfirm}
        secureTextEntry
        onSubmit={create}
      />
      {mismatch ? <Hint testID="password.mismatch" text={EN['password.mismatch']} /> : null}
      <Button testID="signUp.submit" label={EN['signUp.submit']} onPress={create} busy={busy} />
    </Screen>
  );
}

import { useRouter } from 'expo-router';
import { useState } from 'react';
import { useAppContext } from '../../composition/app-context';
import { APP_ROUTES } from '../../keys/routes';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Hint, Title } from '../../ui/Typography';
import { PASSWORD_RULE_KEYS, errorBanner } from './error-text';
import { passwordHints } from './password-hints';
import { useSubmit } from './use-submit';

export function RecoverScreen() {
  const { services } = useAppContext();
  const router = useRouter();
  const [username, setUsername] = useState('');
  const [words, setWords] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [touched, setTouched] = useState(false);
  const { busy, error, submit } = useSubmit();
  const hints = passwordHints(newPassword, username, touched);
  const mismatch = touched && confirm !== newPassword;
  const banner = error === null ? null : errorBanner(error);

  const recover = () => {
    setTouched(true);
    if (passwordHints(newPassword, username, true).length > 0 || confirm !== newPassword) return;
    submit(async () => {
      await services.auth.recover({ username, words, newPassword });
      router.replace(APP_ROUTES.root);
    });
  };

  return (
    <Screen testID="recover.screen" scroll>
      <Title testID="recover.title" text={EN['recover.title']} />
      {banner === null ? null : (
        <Banner testID={banner.testID} kind="error" message={banner.message} />
      )}
      <TextField
        testID="recover.username"
        label={EN['recover.username']}
        value={username}
        onChangeText={setUsername}
      />
      <TextField
        testID="recover.words"
        label={EN['recover.words']}
        value={words}
        onChangeText={setWords}
        multiline
      />
      <TextField
        testID="recover.newPassword"
        label={EN['recover.newPassword']}
        value={newPassword}
        onChangeText={(value) => {
          setNewPassword(value);
          setTouched(true);
        }}
        secureTextEntry
      />
      {hints.map((rule) => (
        <Hint key={rule} testID={PASSWORD_RULE_KEYS[rule]} text={EN[PASSWORD_RULE_KEYS[rule]]} />
      ))}
      <TextField
        testID="recover.confirm"
        label={EN['recover.confirm']}
        value={confirm}
        onChangeText={setConfirm}
        secureTextEntry
        onSubmit={recover}
      />
      {mismatch ? <Hint testID="password.mismatch" text={EN['password.mismatch']} /> : null}
      <Button testID="recover.submit" label={EN['recover.submit']} onPress={recover} busy={busy} />
    </Screen>
  );
}

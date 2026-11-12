import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Switch, Text, View } from 'react-native';
import { platformTraits } from '../../adapters/platform-traits/platform-traits';
import { useAccountState, useAppContext } from '../../composition/app-context';
import { APP_ROUTES } from '../../keys/routes';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Title } from '../../ui/Typography';
import { useTheme } from '../../ui/theme';
import { errorBanner } from './error-text';
import { useSubmit } from './use-submit';

export function SignInScreen() {
  const { services } = useAppContext();
  const state = useAccountState();
  const router = useRouter();
  const { colors } = useTheme();
  const [username, setUsername] = useState(
    state.kind === 'signedOut' ? (state.username ?? '') : '',
  );
  const [password, setPassword] = useState('');
  const [keepSignedIn, setKeepSignedIn] = useState(false);
  const { busy, error, submit } = useSubmit();
  const banner = error === null ? null : errorBanner(error);

  const signIn = () =>
    submit(async () => {
      await services.auth.signIn({ username, password, keepSignedIn });
      router.replace(APP_ROUTES.root);
    });

  return (
    <Screen testID="signIn.screen" scroll>
      <Title testID="signIn.title" text={EN['signIn.title']} />
      {banner === null ? null : (
        <Banner testID={banner.testID} kind="error" message={banner.message} />
      )}
      <TextField
        testID="signIn.username"
        label={EN['signIn.username']}
        value={username}
        onChangeText={setUsername}
        onSubmit={signIn}
      />
      <TextField
        testID="signIn.password"
        label={EN['signIn.password']}
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        onSubmit={signIn}
      />
      {platformTraits.offerKeepSignedIn ? (
        <View>
          <Switch
            testID="signIn.keepSignedIn"
            accessibilityLabel={EN['signIn.keepSignedIn']}
            value={keepSignedIn}
            onValueChange={setKeepSignedIn}
          />
          <Text style={{ color: colors.text }}>{EN['signIn.keepSignedIn']}</Text>
        </View>
      ) : null}
      <Button testID="signIn.submit" label={EN['signIn.submit']} onPress={signIn} busy={busy} />
      <Button
        testID="signIn.forgot"
        label={EN['signIn.forgot']}
        variant="secondary"
        onPress={() => router.push(APP_ROUTES.recover)}
      />
    </Screen>
  );
}

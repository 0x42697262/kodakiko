import { useRouter } from 'expo-router';
import { useState } from 'react';
import { platformTraits } from '../../adapters/platform-traits/platform-traits';
import { useAppContext } from '../../composition/app-context';
import { APP_ROUTES } from '../../keys/routes';
import { AuthError, type AuthFailure } from '../../services/auth-errors';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Body, Title } from '../../ui/Typography';
import { errorBanner } from './error-text';
import { useSubmit } from './use-submit';

const FAILURE_BY_CHOICE: Readonly<Record<string, AuthFailure>> = Object.freeze({
  invalid: 'notKodakiko',
  not_https: 'notHttps',
  unreachable: 'unreachable',
  not_kodakiko: 'notKodakiko',
});

function ServerForm() {
  const { services, inviteToken } = useAppContext();
  const router = useRouter();
  const [input, setInput] = useState('');
  const { busy, error, submit } = useSubmit();
  const banner = error === null ? null : errorBanner(error);

  const choose = () =>
    submit(async () => {
      const choice = await services.auth.chooseServer(input);
      if (!choice.ok) throw new AuthError(FAILURE_BY_CHOICE[choice.reason] ?? 'generic');
      inviteToken.set(choice.inviteToken);
      router.push(choice.inviteToken === null ? APP_ROUTES.signIn : APP_ROUTES.signUp);
    });

  return (
    <>
      {banner === null ? null : (
        <Banner testID={banner.testID} kind="error" message={banner.message} />
      )}
      <TextField
        testID="welcome.serverLabel"
        selectOnFocus
        label={EN['welcome.serverLabel']}
        value={input}
        onChangeText={setInput}
        onSubmit={choose}
      />
      <Button
        testID="welcome.continue"
        label={EN['welcome.continue']}
        onPress={choose}
        busy={busy}
      />
    </>
  );
}

function WebActions() {
  const router = useRouter();
  return (
    <>
      <Button
        testID="welcome.signIn"
        label={EN['welcome.signIn']}
        onPress={() => router.push(APP_ROUTES.signIn)}
      />
      <Button
        testID="welcome.signUp"
        label={EN['welcome.signUp']}
        variant="secondary"
        onPress={() => router.push(APP_ROUTES.signUp)}
      />
    </>
  );
}

export function WelcomeScreen() {
  return (
    <Screen testID="welcome.screen" scroll>
      <Title testID="welcome.title" text={EN['welcome.title']} />
      <Body testID="welcome.subtitle" text={EN['welcome.subtitle']} />
      {platformTraits.chooseServerAtWelcome ? <ServerForm /> : <WebActions />}
    </Screen>
  );
}

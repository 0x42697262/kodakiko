import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { platformTraits } from '../../adapters/platform-traits/platform-traits';
import { useAccountState, useAppContext } from '../../composition/app-context';
import { APP_ROUTES } from '../../keys/routes';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Title } from '../../ui/Typography';
import { errorBanner } from './error-text';
import { useSubmit } from './use-submit';

export function LockScreen() {
  const { services } = useAppContext();
  const router = useRouter();
  const state = useAccountState();
  const [password, setPassword] = useState('');
  const [passwordShown, setPasswordShown] = useState(!platformTraits.deviceUnlock);
  const { busy, error, submit } = useSubmit();
  const leftLock = state.kind !== 'locked';

  useEffect(() => {
    if (leftLock) router.replace(APP_ROUTES.root);
  }, [leftLock, router]);

  const wrongPassword = error?.reason === 'wrongPassword';
  const banner = error === null || wrongPassword ? null : errorBanner(error);

  const unlockWithPassword = () =>
    submit(async () => {
      await services.auth.unlockWithPassword(password);
      router.replace(APP_ROUTES.root);
    });

  const unlockWithDevice = () =>
    submit(async () => {
      if (await services.auth.unlockWithDevice()) router.replace(APP_ROUTES.root);
    });

  return (
    <Screen testID="lock.screen" scroll>
      <Title testID="lock.title" text={EN['lock.title']} />
      {banner === null ? null : (
        <Banner testID={banner.testID} kind="error" message={banner.message} />
      )}
      {platformTraits.deviceUnlock ? (
        <Button
          testID="lock.unlockDevice"
          label={EN['lock.unlockDevice']}
          onPress={unlockWithDevice}
          busy={busy}
        />
      ) : null}
      {passwordShown ? (
        <TextField
          testID="lock.password"
          label={EN['lock.password']}
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          onSubmit={unlockWithPassword}
          errorText={wrongPassword ? EN['lock.wrongPassword'] : null}
          errorTestID="lock.wrongPassword"
        />
      ) : null}
      <Button
        testID="lock.unlockPassword"
        label={EN['lock.unlockPassword']}
        variant={platformTraits.deviceUnlock ? 'secondary' : 'primary'}
        onPress={passwordShown ? unlockWithPassword : () => setPasswordShown(true)}
        busy={passwordShown && busy}
      />
    </Screen>
  );
}

import { useRouter, type Href } from 'expo-router';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { platformTraits } from '../../adapters/platform-traits/platform-traits';
import { useAccountState, useAppContext } from '../../composition/app-context';
import { ROUTE_BY_STATE } from '../../composition/route-by-state';
import { APP_ROUTES } from '../../keys/routes';
import { entryIdOfRoute, nextRouteAfterUnlock } from '../../services/lock/return-to';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Title } from '../../ui/Typography';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { Sheet } from '../gallery/sheet';
import { errorBanner } from './error-text';
import { useSubmit } from './use-submit';

export function LockScreen() {
  const { services, rowStore } = useAppContext();
  const { colors } = useTheme();
  const router = useRouter();
  const state = useAccountState();
  const sync = useSyncExternalStore(services.sync.subscribe, services.sync.status);
  const [password, setPassword] = useState('');
  const [passwordShown, setPasswordShown] = useState(!platformTraits.deviceUnlock);
  const [confirmingSignOut, setConfirmingSignOut] = useState(false);
  const { busy, error, submit } = useSubmit(() => setPassword(''));
  const username = services.auth.activeUsername();

  useEffect(() => {
    if (state.kind === 'locked') return;
    if (state.kind !== 'unlocked') {
      router.replace(ROUTE_BY_STATE[state.kind]);
      return;
    }
    const remembered = services.lock.returnTo.get();
    services.lock.returnTo.set(null);
    const entryId = remembered === null ? null : entryIdOfRoute(remembered);
    const exists =
      entryId === null
        ? Promise.resolve(false)
        : rowStore.transaction((tx) => tx.entries.get(entryId)).then((row) => row !== null);
    exists
      .catch(() => false)
      .then((found) => router.replace(nextRouteAfterUnlock(remembered, found) as Href))
      .catch(() => undefined);
  }, [state.kind, router, services.lock.returnTo, rowStore]);

  useEffect(() => {
    if (!platformTraits.deviceUnlock || !services.lock.lockedInSession()) return;
    services.auth.unlockWithDevice().catch((caught: unknown) => {
      if (caught instanceof Error) console.info('lock.device_unlock_failed', caught.name);
      setPasswordShown(true);
    });
  }, [services.auth, services.lock]);

  const wrongPassword = error?.reason === 'wrongPassword';
  const banner = error === null || wrongPassword ? null : errorBanner(error);

  const unlockWithPassword = () =>
    submit(async () => {
      await services.auth.unlockWithPassword(password);
    });

  const unlockWithDevice = () =>
    submit(async () => {
      if (!(await services.auth.unlockWithDevice())) setPasswordShown(true);
    });

  function signOut(): void {
    setConfirmingSignOut(false);
    services.auth.signOut().catch(() => undefined);
  }

  return (
    <Screen testID="lock.screen" scroll>
      <Title testID="lock.title" text={EN['lock.title']} />
      {username === null ? null : (
        <Text testID="lock.lockedAs" style={[styles.username, { color: colors.textMuted }]}>
          {EN['lock.lockedAs'](username)}
        </Text>
      )}
      {banner === null ? null : <Banner testID={banner.testID} kind="error" message={banner.message} />}
      {platformTraits.deviceUnlock ? (
        <Button testID="lock.unlockDevice" label={EN['lock.unlockDevice']} onPress={unlockWithDevice} busy={busy} />
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
      <View style={styles.links}>
        <Button
          testID="lock.forgot"
          label={EN['lock.forgot']}
          variant="secondary"
          onPress={() =>
            router.push({ pathname: APP_ROUTES.recover, params: username === null ? {} : { username } } as Href)
          }
        />
        <Button
          testID="lock.signOut"
          label={EN['lock.signOut']}
          variant="secondary"
          onPress={() => (sync.queued > 0 ? setConfirmingSignOut(true) : signOut())}
        />
      </View>
      {confirmingSignOut ? (
        <Sheet testID="lock.signOutSheet" onClose={() => setConfirmingSignOut(false)}>
          <Text style={{ color: colors.text }}>{EN['lock.signOutConfirm']}</Text>
          <Button testID="lock.signOutConfirm" label={EN['lock.signOut']} variant="danger" onPress={signOut} />
          <Button testID="lock.cancel" label={EN['lock.cancel']} variant="secondary" onPress={() => setConfirmingSignOut(false)} />
        </Sheet>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  username: { fontSize: FONT_SIZE.body },
  links: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
});

import * as Linking from 'expo-linking';
import { useRouter } from '../../navigation/use-router';
import { useEffect } from 'react';
import { useAppContext } from '../../composition/app-context';
import { APP_ROUTES } from '../../keys/routes';
import { EN } from '../../text/en';
import { Screen } from '../../ui/Screen';
import { Title } from '../../ui/Typography';
import { extractInviteToken } from './invite-input';

export function InviteScreen() {
  const { inviteToken } = useAppContext();
  const router = useRouter();

  useEffect(() => {
    let cancelled = false;
    Linking.getInitialURL()
      .then((url) => {
        if (cancelled) return;
        inviteToken.set(url === null ? null : extractInviteToken(url));
        router.replace(APP_ROUTES.signUp);
      })
      .catch(() => router.replace(APP_ROUTES.signUp));
    return () => {
      cancelled = true;
    };
  }, [inviteToken, router]);

  return (
    <Screen testID="invite.screen">
      <Title testID="invite.title" text={EN['invite.title']} />
    </Screen>
  );
}

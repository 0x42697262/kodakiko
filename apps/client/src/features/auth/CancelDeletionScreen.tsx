import { useRouter } from 'expo-router';
import { DELETION_DATE_LOCALE, DELETION_DATE_OPTIONS } from '../../constants/auth-constants';
import { useAppContext } from '../../composition/app-context';
import { APP_ROUTES } from '../../keys/routes';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { Body, Title } from '../../ui/Typography';
import { useDeletionDueAt } from './auth-hooks';
import { errorBanner } from './error-text';
import { useSubmit } from './use-submit';

export function CancelDeletionScreen() {
  const { services } = useAppContext();
  const router = useRouter();
  const dueAt = useDeletionDueAt();
  const { busy, error, submit } = useSubmit();
  const banner = error === null ? null : errorBanner(error);
  const date =
    dueAt === null
      ? ''
      : new Date(dueAt).toLocaleDateString(DELETION_DATE_LOCALE, DELETION_DATE_OPTIONS);

  return (
    <Screen testID="cancelDeletion.screen" scroll>
      <Title testID="cancelDeletion.title" text={EN['cancelDeletion.title']} />
      {banner === null ? null : (
        <Banner testID={banner.testID} kind="error" message={banner.message} />
      )}
      <Body testID="cancelDeletion.body" text={EN['cancelDeletion.body'](date)} />
      <Button
        testID="cancelDeletion.submit"
        label={EN['cancelDeletion.submit']}
        busy={busy}
        onPress={() =>
          submit(async () => {
            await services.auth.cancelDeletion();
            router.replace(APP_ROUTES.root);
          })
        }
      />
      <Button
        testID="cancelDeletion.signOut"
        label={EN['cancelDeletion.signOut']}
        variant="secondary"
        onPress={() =>
          submit(async () => {
            await services.auth.signOut();
            router.replace(APP_ROUTES.root);
          })
        }
      />
    </Screen>
  );
}

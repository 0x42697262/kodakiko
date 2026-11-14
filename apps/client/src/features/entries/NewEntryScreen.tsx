import type { Href } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { useAppContext } from '../../composition/app-context';
import { APP_ROUTES } from '../../keys/routes';
import { ENTRY_IDS } from '../../keys/entry-ids';
import { useRouter } from '../../navigation/use-router';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Screen } from '../../ui/Screen';
import { Spinner } from '../../ui/Spinner';

export function NewEntryScreen() {
  const router = useRouter();
  const { services } = useAppContext();
  const started = useRef(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    if (services.captureTray.items.length === 0) {
      router.replace(APP_ROUTES.capture as Href);
      return;
    }
    services.entries
      .createFromTray({ note: '', tags: [], time: null })
      .then((entryId) =>
        router.replace({ pathname: '/entry/[id]/edit', params: { id: entryId, new: '1' } } as Href),
      )
      .catch(() => setFailed(true));
  }, [router, services.captureTray, services.entries]);

  return (
    <Screen testID="edit.creating">
      {failed ? (
        <Banner testID={ENTRY_IDS.composeFailure} kind="error" message={EN['errors.generic']} />
      ) : (
        <Spinner testID="edit.creatingSpinner" />
      )}
    </Screen>
  );
}

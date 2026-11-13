import { useEffect, useState, useSyncExternalStore } from 'react';
import { useAppContext } from '../../composition/app-context';
import { SAVED_BANNER_MS } from '../../constants/entry-constants';
import { ENTRY_IDS } from '../../keys/entry-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';

export function SavedBanner() {
  const { services, clock } = useAppContext();
  const savedAt = useSyncExternalStore(services.savedNotice.subscribe, services.savedNotice.get);
  const remaining = savedAt === null ? 0 : SAVED_BANNER_MS - (clock.now() - savedAt);
  const [expired, setExpired] = useState(false);

  useEffect(() => {
    setExpired(false);
    if (savedAt === null || remaining <= 0) return undefined;
    const timer = setTimeout(() => setExpired(true), remaining);
    return () => clearTimeout(timer);
  }, [savedAt]);

  if (savedAt === null || remaining <= 0 || expired) return null;
  return <Banner testID={ENTRY_IDS.savedBanner} kind="info" message={EN['entry.saved']} />;
}

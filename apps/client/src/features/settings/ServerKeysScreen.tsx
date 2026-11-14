import { useEffect, useState, useSyncExternalStore } from 'react';
import { useAccountMode, useAccountState, useAppContext } from '../../composition/app-context';
import { loadAccount } from '../../services/account-storage';
import { EN } from '../../text/en';
import { DetailSectionsView } from '../viewer/EntryDetailsScreen';
import type { DetailSection } from '../viewer/entry-details-model';
import { serverFactSections } from './server-facts';
import { SettingsPage } from './settings-ui';

export function ServerKeysScreen() {
  const { services, rowStore, serverUrl, keyVault, activeAccountKey } = useAppContext();
  const state = useAccountState();
  const local = useAccountMode() === 'local';
  const user = useSyncExternalStore(services.account.user.subscribe, services.account.user.get);
  const settings = useSyncExternalStore(services.deviceSettings.subscribe, services.deviceSettings.get);
  const [sections, setSections] = useState<readonly DetailSection[]>([]);

  useEffect(() => {
    let cancelled = false;
    const load = async (): Promise<void> => {
      const stored = await loadAccount(rowStore);
      if (stored === null) return;
      const url = local ? null : await serverUrl.current();
      const capabilities = await keyVault.capabilities(activeAccountKey.get() ?? undefined).catch(() => ({ persistent: false, biometric: false }));
      if (cancelled) return;
      setSections(
        serverFactSections({
          serverUrl: url,
          config: services.config.get(),
          user,
          account: { userId: stored.userId, username: stored.username, opsLimit: stored.bundle.opsLimit, memLimitBytes: stored.bundle.memLimitBytes, recoveryConfirmed: stored.recoveryConfirmed },
          fingerprint: local ? '' : services.sharing.myFingerprint(),
          keyStorage: { persistent: capabilities.persistent, biometric: capabilities.biometric },
          encryptThumbnails: settings.encryptThumbnails,
          local,
        }),
      );
    };
    load().catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [rowStore, serverUrl, keyVault, activeAccountKey, services, user, settings.encryptThumbnails, local, state.kind]);

  return (
    <SettingsPage testID="server.screen" title={EN['server.title']}>
      <DetailSectionsView sections={sections} />
    </SettingsPage>
  );
}

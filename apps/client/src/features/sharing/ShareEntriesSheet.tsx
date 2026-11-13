import { COLLECTION_NAME_MAX_CHARS } from '@kodakiko/contract';
import { useRouter, type Href } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { listTestId, testId } from '../../keys/test-ids';
import type { AddEntriesResult } from '../../services/sharing/sharing-service';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Body } from '../../ui/Typography';
import { SPACING } from '../../ui/theme-constants';
import { sharingErrorText } from './sharing-errors';
import { SHARING_IDS } from './sharing-ids';
import { LINK_IDS } from '../links/links-ids';
import { useSharedCollections } from './use-shared-collections';

type Panel = 'menu' | 'journals' | 'new';

function resultText(result: AddEntriesResult): string {
  const parts = [EN['shared.added'](result.added.length)];
  if (result.notSynced.length > 0) parts.push(EN['shared.notSynced'](result.notSynced.length));
  if (result.alreadyIn.length > 0) parts.push(EN['shared.alreadyIn'](result.alreadyIn.length));
  if (result.failed.length > 0) parts.push(EN['shared.failed'](result.failed.length));
  return parts.join(' ');
}

export function ShareEntriesSheet({ entryIds }: { readonly entryIds: readonly string[] }) {
  const { services } = useAppContext();
  const collections = useSharedCollections();
  const [panel, setPanel] = useState<Panel>('menu');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ readonly kind: 'info' | 'error'; readonly text: string } | null>(null);
  const enabled = services.config.get()?.sharingEnabled === true;
  const linksEnabled = services.config.get()?.publicLinksEnabled === true;
  const router = useRouter();
  const mine = collections?.filter((collection) => collection.role === 'owner') ?? [];

  async function addTo(collectionId: () => Promise<string>): Promise<void> {
    setBusy(true);
    setMessage(null);
    try {
      const result = await services.sharing.addEntries(await collectionId(), entryIds);
      setMessage({ kind: 'info', text: resultText(result) });
      setPanel('menu');
    } catch (caught) {
      setMessage({ kind: 'error', text: sharingErrorText(caught) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <View testID={SHARING_IDS.shareSheet} style={styles.container}>
      {message === null ? null : <Banner testID={SHARING_IDS.shareResult} kind={message.kind} message={message.text} />}
      {panel === 'menu' ? (
        <>
          <Button testID={SHARING_IDS.addTo} label={EN['shared.addTo']} variant="secondary" disabled={!enabled} onPress={() => setPanel('journals')} />
          {enabled ? null : <Body testID={testId('shared.sharingOffHint')} text={EN['shared.sharingOffHint']} />}
          <Button
            testID={LINK_IDS.createFromSheet}
            label={EN['links.createLink']}
            variant="secondary"
            disabled={!linksEnabled}
            onPress={() => router.push(`/links/new?entries=${entryIds.join(',')}` as Href)}
          />
          {linksEnabled ? null : <Body testID={testId('links.offHint')} text={EN['links.offHint']} />}
        </>
      ) : null}
      {panel === 'journals' ? (
        <>
          {mine.map((collection, position) => (
            <Button
              key={collection.id}
              testID={listTestId(SHARING_IDS.journalRow, position)}
              label={collection.name}
              variant="secondary"
              busy={busy}
              onPress={() => addTo(async () => collection.id).catch(() => undefined)}
            />
          ))}
          <Button testID={SHARING_IDS.newJournal} label={EN['shared.new']} variant="secondary" onPress={() => setPanel('new')} />
        </>
      ) : null}
      {panel === 'new' ? (
        <>
          <TextField testID={testId('shared.nameField')} label={EN['shared.nameField']} value={name} onChangeText={setName} maxLength={COLLECTION_NAME_MAX_CHARS} />
          <Button
            testID={testId('shared.create')}
            label={EN['shared.create']}
            busy={busy}
            disabled={name.trim().length === 0}
            onPress={() => addTo(() => services.sharing.createJournal(name.trim())).catch(() => undefined)}
          />
        </>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({ container: { gap: SPACING.sm } });

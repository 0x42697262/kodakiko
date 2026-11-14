import { useState } from 'react';
import { useTagChoices } from '../tags/tag-picker';
import { StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { ShareEntriesSheet } from '../sharing/ShareEntriesSheet';
import { sharingErrorText } from '../sharing/sharing-errors';
import { ENTRY_IDS } from '../../keys/entry-ids';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import type { BulkResult } from '../../services/entries/entries-service';

export type BulkActionsMode = 'journal' | 'trash' | 'collectionOwner';

export type BulkActionsSheetProps = {
  readonly ids: readonly string[];
  readonly mode: BulkActionsMode;
  readonly collectionId?: string;
  readonly onDone?: () => void;
};

type Panel = 'menu' | 'addTag' | 'removeTag' | 'confirmDelete' | 'share';

function resultMessage(result: BulkResult): string {
  const updated = EN['bulk.updated'](result.changed);
  return result.skipped > 0 ? `${updated} ${EN['bulk.skipped'](result.skipped)}` : updated;
}

export function BulkActionsSheet({ ids, mode, collectionId, onDone }: BulkActionsSheetProps) {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const [panel, setPanel] = useState<Panel>('menu');
  const [tag, setTag] = useState('');
  const choices = useTagChoices();
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<BulkResult>): Promise<void> {
    setBusy(true);
    try {
      setMessage(resultMessage(await action()));
      setPanel('menu');
      setTag('');
      onDone?.();
    } catch {
      setMessage(EN['errors.generic']);
    } finally {
      setBusy(false);
    }
  }

  const resultBanner =
    message === null ? null : <Banner testID={ENTRY_IDS.bulkResult} kind="info" message={message} />;

  if (mode === 'collectionOwner') {
    return (
      <View testID={ENTRY_IDS.bulkMenu} style={styles.container}>
        {resultBanner}
        <Button
          testID={testId('bulk.removeFromJournal')}
          label={EN['bulk.removeFromJournal']}
          variant="secondary"
          busy={busy}
          disabled={collectionId === undefined}
          onPress={() => {
            if (collectionId === undefined) return;
            setBusy(true);
            services.sharing
              .removeEntries(collectionId, ids)
              .then(() => {
                setMessage(EN['bulk.updated'](ids.length));
                onDone?.();
              })
              .catch((caught: unknown) => setMessage(sharingErrorText(caught)))
              .finally(() => setBusy(false));
          }}
        />
      </View>
    );
  }

  if (panel === 'share') {
    return <ShareEntriesSheet entryIds={ids} />;
  }

  if (panel === 'confirmDelete') {
    return (
      <View testID={ENTRY_IDS.bulkDeletePanel} style={styles.container}>
        <Text style={[styles.title, { color: colors.text }]}>
          {EN['bulk.confirmDeleteTitle'](ids.length)}
        </Text>
        <View style={styles.row}>
          <Button
            testID={testId('bulk.confirmDelete')}
            label={EN['bulk.confirmDelete']}
            variant="danger"
            busy={busy}
            onPress={() => void run(() => services.entries.deletePermanently(ids))}
          />
          <Button
            testID={testId('bulk.cancel')}
            label={EN['bulk.cancel']}
            variant="secondary"
            onPress={() => setPanel('menu')}
          />
        </View>
      </View>
    );
  }

  if (panel === 'addTag' || panel === 'removeTag') {
    const adding = panel === 'addTag';
    return (
      <View testID={ENTRY_IDS.bulkTagPanel} style={styles.container}>
        <View style={styles.row}>
          {choices.map((name) => (
            <Button
              key={name}
              testID={`bulk.tagOption.${name}`}
              label={name}
              variant={tag === name ? 'primary' : 'secondary'}
              onPress={() => setTag(name)}
            />
          ))}
        </View>
        {choices.length === 0 ? <Text style={{ color: colors.textMuted }}>{EN['tags.noneToPick']}</Text> : null}
        <View style={styles.row}>
          <Button
            testID={testId(adding ? 'bulk.confirmAdd' : 'bulk.confirmRemove')}
            label={adding ? EN['bulk.confirmAdd'] : EN['bulk.confirmRemove']}
            busy={busy}
            disabled={tag === ''}
            onPress={() =>
              void run(() =>
                adding
                  ? services.entries.addTagToMany(ids, tag)
                  : services.entries.removeTagFromMany(ids, tag),
              )
            }
          />
          <Button
            testID={testId('bulk.cancel')}
            label={EN['bulk.cancel']}
            variant="secondary"
            onPress={() => setPanel('menu')}
          />
        </View>
      </View>
    );
  }

  return (
    <View testID={ENTRY_IDS.bulkMenu} style={styles.container}>
      {resultBanner}
      {mode === 'journal' ? (
        <>
          <Button
            testID={testId('bulk.addTag')}
            label={EN['bulk.addTag']}
            variant="secondary"
            onPress={() => setPanel('addTag')}
          />
          <Button
            testID={testId('bulk.removeTag')}
            label={EN['bulk.removeTag']}
            variant="secondary"
            onPress={() => setPanel('removeTag')}
          />
          <Button testID="bulk.favorite" label={EN['bulk.favorite']} variant="secondary" busy={busy} onPress={() => void run(() => services.entries.setFavorite(ids, true))} />
          <Button testID="bulk.unfavorite" label={EN['bulk.unfavorite']} variant="secondary" busy={busy} onPress={() => void run(() => services.entries.setFavorite(ids, false))} />
          <Button
            testID={testId('bulk.trash')}
            label={EN['bulk.trash']}
            variant="secondary"
            busy={busy}
            onPress={() => void run(() => services.entries.trash(ids))}
          />
          {services.config.get()?.sharingEnabled === true || services.config.get()?.publicLinksEnabled === true ? (
            <Button testID={testId('bulk.share')} label={EN['bulk.share']} variant="secondary" onPress={() => setPanel('share')} />
          ) : null}
        </>
      ) : (
        <>
          <Button
            testID={testId('bulk.restore')}
            label={EN['bulk.restore']}
            variant="secondary"
            busy={busy}
            onPress={() => void run(() => services.entries.restore(ids))}
          />
          <Button
            testID={testId('bulk.deletePermanently')}
            label={EN['bulk.deletePermanently']}
            variant="danger"
            onPress={() => setPanel('confirmDelete')}
          />
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: SPACING.sm },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  title: { fontSize: FONT_SIZE.headline },
});

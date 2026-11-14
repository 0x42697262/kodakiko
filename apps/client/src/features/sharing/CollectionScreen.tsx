import { COLLECTION_NAME_MAX_CHARS } from '@kodakiko/contract';
import { useFocusEffect, useRouter, type Href } from 'expo-router';
import { useCallback, useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { IconAction } from '../../ui/IconAction';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Body } from '../../ui/Typography';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { BulkActionsSheet } from '../entries/bulk-actions-sheet';
import { EntryGrid } from '../gallery/entry-grid';
import { EMPTY_GALLERY_FILTER } from '../gallery/filter-model';
import { Sheet } from '../gallery/sheet';
import { useSelection, type Selection } from '../gallery/use-selection';
import { sharingErrorText } from './sharing-errors';
import { SHARING_IDS } from './sharing-ids';
import { useSharedCollection } from './use-shared-collections';

type Overlay = 'none' | 'more' | 'rename' | 'confirmDelete' | 'confirmLeave' | 'actions';

const NO_SELECTION: Selection = Object.freeze({
  selecting: false,
  selected: new Set<string>(),
  start: () => undefined,
  tap: () => false,
  clear: () => undefined,
  selectAll: () => undefined,
});

export function CollectionScreen({ collectionId }: { readonly collectionId: string }) {
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const collection = useSharedCollection(collectionId);
  const ownerSelection = useSelection();
  const [overlay, setOverlay] = useState<Overlay>('none');
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const owner = collection?.role === 'owner';
  const selection = owner ? ownerSelection : NO_SELECTION;
  const filter = useMemo(
    () => ({ ...EMPTY_GALLERY_FILTER, scope: { kind: 'collection' as const, collectionId } }),
    [collectionId],
  );

  useFocusEffect(
    useCallback(
      () => () => {
        ownerSelection.clear();
        setOverlay('none');
      },
      [ownerSelection.clear],
    ),
  );

  if (collection === undefined)
    return <Screen testID={SHARING_IDS.collectionScreen}>{null}</Screen>;
  if (collection === null) {
    return (
      <Screen testID={SHARING_IDS.collectionScreen}>
        <IconAction
          testID={testId('shared.back')}
          label={EN['shared.back']}
          onPress={() => (router.canGoBack() ? router.back() : router.replace('/shared' as Href))}
          icon="back"
          tonal={false}
        />
      </Screen>
    );
  }

  async function act(work: () => Promise<void>, after: () => void): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await work();
      setOverlay('none');
      after();
    } catch (caught) {
      setError(sharingErrorText(caught));
    } finally {
      setBusy(false);
    }
  }

  const backToShared = () => {
    if (router.canGoBack()) router.back();
    else router.replace('/shared' as Href);
  };

  return (
    <Screen testID={SHARING_IDS.collectionScreen}>
      <View style={styles.header}>
        <IconAction
          testID={testId('shared.back')}
          label={EN['shared.back']}
          onPress={() => router.back()}
          icon="back"
          tonal={false}
        />
        <View style={styles.titleBlock}>
          <Text
            testID={SHARING_IDS.collectionTitle}
            accessibilityRole="header"
            style={[styles.title, { color: colors.text }]}
          >
            {collection.name}
          </Text>
          {owner ? null : (
            <Text testID={SHARING_IDS.collectionSubtitle} style={{ color: colors.textMuted }}>
              {EN['shared.sharedBy'](collection.ownerUsername)}
            </Text>
          )}
        </View>
        {selection.selecting ? (
          <Button
            testID={testId('gallery.actions')}
            label={EN['gallery.actions']}
            disabled={selection.selected.size === 0}
            onPress={() => setOverlay('actions')}
          />
        ) : (
          <Button
            testID={SHARING_IDS.collectionMore}
            label={EN['shared.more']}
            variant="secondary"
            onPress={() => setOverlay('more')}
          />
        )}
      </View>
      <EntryGrid
        filter={filter}
        showOnThisDay={false}
        selection={selection}
        empty={<Body testID={testId('gallery.empty')} text={EN['gallery.empty']} />}
      />
      {overlay === 'more' ? (
        <Sheet testID={SHARING_IDS.collectionSheet} onClose={() => setOverlay('none')}>
          {owner ? (
            <>
              <Button
                testID={SHARING_IDS.shareWith}
                label={EN['shared.shareWith']}
                variant="secondary"
                onPress={() => {
                  setOverlay('none');
                  router.push(`/collections/${collectionId}/share` as Href);
                }}
              />
              <Button
                testID={SHARING_IDS.rename}
                label={EN['shared.rename']}
                variant="secondary"
                onPress={() => {
                  setName(collection.name);
                  setOverlay('rename');
                }}
              />
              <Button
                testID={SHARING_IDS.delete}
                label={EN['shared.delete']}
                variant="danger"
                onPress={() => setOverlay('confirmDelete')}
              />
            </>
          ) : (
            <Button
              testID={SHARING_IDS.leave}
              label={EN['shared.leave']}
              variant="danger"
              onPress={() => setOverlay('confirmLeave')}
            />
          )}
        </Sheet>
      ) : null}
      {overlay === 'rename' ? (
        <Sheet testID={SHARING_IDS.collectionSheet} onClose={() => setOverlay('none')}>
          <TextField
            testID={testId('shared.nameField')}
            label={EN['shared.nameField']}
            value={name}
            onChangeText={setName}
            maxLength={COLLECTION_NAME_MAX_CHARS}
          />
          {error === null ? null : (
            <Banner testID={SHARING_IDS.error} kind="error" message={error} />
          )}
          <Button
            testID={testId('shared.save')}
            label={EN['shared.save']}
            busy={busy}
            disabled={name.trim().length === 0}
            onPress={() =>
              act(
                () => services.sharing.renameJournal(collectionId, name.trim()),
                () => services.collectionNames.clear(),
              ).catch(() => undefined)
            }
          />
        </Sheet>
      ) : null}
      {overlay === 'confirmDelete' ? (
        <Sheet testID={SHARING_IDS.collectionSheet} onClose={() => setOverlay('none')}>
          <Text style={{ color: colors.text }}>{EN['shared.deleteConfirm'](collection.name)}</Text>
          {error === null ? null : (
            <Banner testID={SHARING_IDS.error} kind="error" message={error} />
          )}
          <Button
            testID={SHARING_IDS.deleteConfirm}
            label={EN['shared.deleteAction']}
            variant="danger"
            busy={busy}
            onPress={() =>
              act(() => services.sharing.deleteJournal(collectionId), backToShared).catch(
                () => undefined,
              )
            }
          />
        </Sheet>
      ) : null}
      {overlay === 'confirmLeave' ? (
        <Sheet testID={SHARING_IDS.collectionSheet} onClose={() => setOverlay('none')}>
          <Text style={{ color: colors.text }}>
            {EN['shared.leaveConfirm'](collection.name, collection.ownerUsername)}
          </Text>
          {error === null ? null : (
            <Banner testID={SHARING_IDS.error} kind="error" message={error} />
          )}
          <Button
            testID={SHARING_IDS.leaveConfirm}
            label={EN['shared.leaveAction']}
            variant="danger"
            busy={busy}
            onPress={() =>
              act(() => services.sharing.leave(collectionId), backToShared).catch(() => undefined)
            }
          />
        </Sheet>
      ) : null}
      {overlay === 'actions' ? (
        <Sheet
          testID={SHARING_IDS.collectionSheet}
          onClose={() => {
            setOverlay('none');
            ownerSelection.clear();
          }}
        >
          <BulkActionsSheet
            ids={[...selection.selected]}
            mode="collectionOwner"
            collectionId={collectionId}
          />
        </Sheet>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm },
  titleBlock: { flex: 1, gap: SPACING.xs },
  title: { fontSize: FONT_SIZE.title, fontWeight: '700' },
});

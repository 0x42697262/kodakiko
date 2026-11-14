import { type Href } from 'expo-router';
import { useRouter } from '../../navigation/use-router';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Touchable } from '../../ui/Touchable';
import { useAccountMode, useAppContext } from '../../composition/app-context';
import { listTestId, testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Body, Title } from '../../ui/Typography';
import { BORDER_WIDTH, FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { Sheet } from '../gallery/sheet';
import { COLLECTION_NAME_MAX_CHARS as COLLECTION_NAME_MAX } from '@kodakiko/contract';
import { sharingErrorText } from './sharing-errors';
import { SHARING_IDS } from './sharing-ids';
import { useSharedCollections, type SharedCollection } from './use-shared-collections';
import { usePullToSync } from '../sync/use-pull-to-sync';

function Row({
  testID,
  collection,
  detail,
  onPress,
}: {
  readonly testID: string;
  readonly collection: SharedCollection;
  readonly detail: string;
  readonly onPress: () => void;
}) {
  const { colors } = useTheme();
  return (
    <Touchable
      testID={testID}
      accessibilityRole="button"
      onPress={onPress}
      style={[styles.row, { borderColor: colors.border, backgroundColor: colors.surface }]}
    >
      <Text style={[styles.name, { color: colors.text }]}>{collection.name}</Text>
      <Text style={[styles.detail, { color: colors.textMuted }]}>{detail}</Text>
    </Touchable>
  );
}

export function SharedScreen() {
  const pullToSync = usePullToSync();
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const collections = useSharedCollections();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const local = useAccountMode() === 'local';
  const enabled = !local && services.config.get()?.sharingEnabled === true;

  if (!enabled) {
    return (
      <Screen testID={SHARING_IDS.screen}>
        <Title text={EN['shared.tab']} />
        <Body
          testID={SHARING_IDS.flagOff}
          text={local ? EN['shared.localOnly'] : EN['shared.flagOff']}
        />
      </Screen>
    );
  }

  const mine = collections?.filter((collection) => collection.role === 'owner') ?? [];
  const withMe = collections?.filter((collection) => collection.role === 'viewer') ?? [];
  const open = (id: string) => router.push(`/collections/${id}` as Href);

  async function create(): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      const id = await services.sharing.createJournal(name.trim());
      setCreating(false);
      setName('');
      open(id);
    } catch (caught) {
      setError(sharingErrorText(caught));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Screen testID={SHARING_IDS.screen} scroll refreshControl={pullToSync}>
      <Title text={EN['shared.tab']} />
      <View style={styles.section}>
        <Text style={[styles.heading, { color: colors.text }]}>{EN['shared.mine']}</Text>
        {collections !== null && mine.length === 0 ? (
          <Body testID={SHARING_IDS.emptyMine} text={EN['shared.emptyMine']} />
        ) : null}
        {mine.map((collection, position) => (
          <Row
            key={collection.id}
            testID={listTestId(SHARING_IDS.mineRow, position)}
            collection={collection}
            detail={EN['shared.entryCount'](collection.entryCount)}
            onPress={() => open(collection.id)}
          />
        ))}
        <Button
          testID={testId('shared.new')}
          label={EN['shared.new']}
          variant="secondary"
          onPress={() => setCreating(true)}
        />
      </View>
      <View style={styles.section}>
        <Text style={[styles.heading, { color: colors.text }]}>{EN['shared.withMe']}</Text>
        {collections !== null && withMe.length === 0 ? (
          <Body testID={SHARING_IDS.emptyWithMe} text={EN['shared.emptyWithMe']} />
        ) : null}
        {withMe.map((collection, position) => (
          <Row
            key={collection.id}
            testID={listTestId(SHARING_IDS.withMeRow, position)}
            collection={collection}
            detail={EN['shared.from'](collection.ownerUsername)}
            onPress={() => open(collection.id)}
          />
        ))}
      </View>
      {creating ? (
        <Sheet testID={SHARING_IDS.newSheet} onClose={() => setCreating(false)}>
          <TextField
            testID={testId('shared.nameField')}
            label={EN['shared.nameField']}
            value={name}
            onChangeText={setName}
            maxLength={COLLECTION_NAME_MAX}
          />
          {error === null ? null : (
            <Banner testID={SHARING_IDS.error} kind="error" message={error} />
          )}
          <Button
            testID={testId('shared.create')}
            label={EN['shared.create']}
            busy={busy}
            disabled={name.trim().length === 0}
            onPress={() => create().catch(() => undefined)}
          />
          <Button
            testID={testId('shared.cancel')}
            label={EN['shared.cancel']}
            variant="secondary"
            onPress={() => setCreating(false)}
          />
        </Sheet>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  section: { gap: SPACING.sm },
  heading: { fontSize: FONT_SIZE.headline, fontWeight: '600' },
  row: { padding: SPACING.md, borderRadius: RADIUS.md, borderWidth: BORDER_WIDTH, gap: SPACING.xs },
  name: { fontSize: FONT_SIZE.body, fontWeight: '600' },
  detail: { fontSize: FONT_SIZE.caption },
});

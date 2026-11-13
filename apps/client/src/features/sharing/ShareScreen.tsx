import type { ShareDto } from '@kodakiko/contract';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { listTestId } from '../../keys/test-ids';
import type { Recipient } from '../../services/sharing/sharing-service';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Body, Title } from '../../ui/Typography';
import { BORDER_WIDTH, FONT_FAMILY_MONO, FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { Sheet } from '../gallery/sheet';
import { sharingErrorText } from './sharing-errors';
import { SHARING_IDS } from './sharing-ids';

export function ShareScreen({ collectionId }: { readonly collectionId: string }) {
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const [username, setUsername] = useState('');
  const [found, setFound] = useState<Recipient | null>(null);
  const [recipients, setRecipients] = useState<readonly ShareDto[]>([]);
  const [stopping, setStopping] = useState<ShareDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(() => {
    services.sharing
      .listShares(collectionId)
      .then(setRecipients)
      .catch((caught: unknown) => setError(sharingErrorText(caught)));
  }, [services.sharing, collectionId]);

  useEffect(reload, [reload]);

  async function run(work: () => Promise<void>, recipientName = ''): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (caught) {
      setError(sharingErrorText(caught, recipientName));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Screen testID={SHARING_IDS.shareScreen} scroll>
      <Button testID="shared.back" label={EN['shared.back']} variant="secondary" onPress={() => router.back()} />
      <Title text={EN['shared.shareTitle']} />
      <TextField
        testID={SHARING_IDS.username}
        label={EN['shared.usernameField']}
        value={username}
        onChangeText={(value) => {
          setUsername(value);
          setFound(null);
        }}
        autoCapitalize="none"
        onSubmit={() => run(async () => setFound(await services.sharing.lookupRecipient(username)))}
      />
      <Button
        testID={SHARING_IDS.find}
        label={EN['shared.find']}
        busy={busy && found === null}
        disabled={username.trim().length === 0}
        onPress={() => run(async () => setFound(await services.sharing.lookupRecipient(username))).catch(() => undefined)}
      />
      {error === null ? null : <Banner testID={SHARING_IDS.error} kind="error" message={error} />}
      {found === null ? null : (
        <View style={[styles.card, { borderColor: colors.border, backgroundColor: colors.surface }]}>
          <Text style={[styles.name, { color: colors.text }]}>{found.username}</Text>
          <Text testID={SHARING_IDS.securityCode} selectable style={[styles.code, { color: colors.text }]}>
            {found.fingerprint}
          </Text>
          <Body text={EN['shared.securityCodeHelp'](found.username)} />
          <View style={styles.row}>
            <Button
              testID={SHARING_IDS.confirmShare}
              label={EN['shared.share']}
              busy={busy}
              onPress={() =>
                run(async () => {
                  await services.sharing.share(collectionId, found);
                  setFound(null);
                  setUsername('');
                  reload();
                }, found.username).catch(() => undefined)
              }
            />
            <Button testID={SHARING_IDS.cancelShare} label={EN['shared.cancel']} variant="secondary" onPress={() => setFound(null)} />
          </View>
        </View>
      )}
      <View style={styles.section}>
        <Text style={[styles.name, { color: colors.text }]}>{EN['shared.recipients']}</Text>
        {recipients.map((recipient, position) => (
          <View key={recipient.recipientUserId} testID={listTestId(SHARING_IDS.recipientRow, position)} style={styles.row}>
            <Text style={[styles.recipient, { color: colors.text }]}>{recipient.recipientUsername}</Text>
            <Button
              testID={listTestId(SHARING_IDS.removeRecipient, position)}
              label={EN['shared.remove']}
              variant="secondary"
              onPress={() => setStopping(recipient)}
            />
          </View>
        ))}
      </View>
      {stopping === null ? null : (
        <Sheet testID={SHARING_IDS.collectionSheet} onClose={() => setStopping(null)}>
          <Text style={{ color: colors.text }}>{EN['shared.stopConfirm'](stopping.recipientUsername)}</Text>
          <Button
            testID={SHARING_IDS.stopConfirm}
            label={EN['shared.stopAction']}
            variant="danger"
            busy={busy}
            onPress={() =>
              run(async () => {
                await services.sharing.unshare(collectionId, stopping.recipientUserId);
                setStopping(null);
                reload();
              }).catch(() => undefined)
            }
          />
        </Sheet>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  card: { padding: SPACING.lg, gap: SPACING.md, borderRadius: RADIUS.md, borderWidth: BORDER_WIDTH },
  name: { fontSize: FONT_SIZE.headline, fontWeight: '600' },
  code: { fontFamily: FONT_FAMILY_MONO, fontSize: FONT_SIZE.title },
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm, flexWrap: 'wrap' },
  recipient: { flex: 1, fontSize: FONT_SIZE.body },
  section: { gap: SPACING.sm },
});

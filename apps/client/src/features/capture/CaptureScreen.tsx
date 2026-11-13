import { useRouter, type Href } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { CaptureSurface } from '../../adapters/capture/capture-surface';
import { useAppContext } from '../../composition/app-context';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import { CAPTURE_IDS } from '../../keys/capture-keys';
import { APP_ROUTES } from '../../keys/routes';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { FAB } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Button } from '../../ui/Button';
import { IconAction } from '../../ui/IconAction';
import { ICONS } from '../../ui/icons';
import { Screen } from '../../ui/Screen';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { TrayStrip } from './TrayStrip';
import { useCaptureImport } from './use-capture-import';

export type CaptureScreenProps = { readonly addToEntry?: { readonly entryId: string; readonly currentCount: number } };

export function CaptureScreen({ addToEntry }: CaptureScreenProps = {}) {
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  const configLimit = (services.config.get() ?? CONFIG_FALLBACK).maxMediaPerEntry;
  const limitOverride = addToEntry === undefined ? null : Math.max(0, configLimit - addToEntry.currentCount);
  const { items, pending, messages, maxMediaPerEntry, addInputs, remove } = useCaptureImport(limitOverride);
  const [confirmingDiscard, setConfirmingDiscard] = useState(false);
  const [leftover, setLeftover] = useState(() => (addToEntry === undefined ? 0 : services.captureTray.items.length));
  const [adding, setAdding] = useState(false);
  const maxUploadBytes = (services.config.get() ?? CONFIG_FALLBACK).maxUploadBytes;
  const empty = items.length === 0 && pending === 0;

  function leave(): void {
    if (router.canGoBack()) router.back();
    else router.replace(APP_ROUTES.root);
  }

  function close(): void {
    if (empty) leave();
    else setConfirmingDiscard(true);
  }

  async function discard(): Promise<void> {
    await services.captureTray.clear(true);
    leave();
  }

  async function addToExisting(): Promise<void> {
    if (addToEntry === undefined) return;
    setAdding(true);
    try {
      await services.entries.addMedia(addToEntry.entryId, services.captureTray.items);
      await services.captureTray.clear(false);
      leave();
    } catch {
      setAdding(false);
    }
  }

  if (leftover > 0) {
    return (
      <Screen testID={CAPTURE_IDS.screen}>
        <View testID={CAPTURE_IDS.discardPanel} style={styles.panel}>
          <Text style={[styles.panelText, { color: colors.text }]}>{EN['capture.discardInProgress'](leftover)}</Text>
          <View style={styles.row}>
            <Button
              testID={testId('capture.discard')}
              label={EN['capture.discard']}
              variant="danger"
              onPress={() => {
                services.captureTray
                  .clear(true)
                  .then(() => setLeftover(0))
                  .catch(() => undefined);
              }}
            />
            <Button testID={testId('capture.cancelAdd')} label={EN['capture.cancelAdd']} variant="secondary" onPress={leave} />
          </View>
        </View>
      </Screen>
    );
  }

  return (
    <SafeAreaView testID={CAPTURE_IDS.screen} edges={CAPTURE_EDGES} style={styles.root}>
      <View style={styles.surface}>
        <CaptureSurface
          onInputs={addInputs}
          remainingSlots={Math.max(0, maxMediaPerEntry - items.length - pending)}
          maxUploadBytes={maxUploadBytes}
        />
        <View style={styles.topBar}>
          <IconAction testID={testId('capture.close')} icon="close" label={EN['capture.close']} onPress={close} />
        </View>
      </View>
      {messages.length > 0 ? (
        <Banner testID={CAPTURE_IDS.refusals} kind="warning" message={messages.join('\n')} />
      ) : null}
      {confirmingDiscard ? (
        <View testID={CAPTURE_IDS.discardPanel} style={[styles.panel, { backgroundColor: colors.background }]}>
          <Text style={[styles.panelText, { color: colors.text }]}>
            {EN['capture.discardTitle'](items.length)}
          </Text>
          <View style={styles.row}>
            <Button
              testID={testId('capture.discard')}
              label={EN['capture.discard']}
              variant="danger"
              onPress={() => void discard()}
            />
            <Button
              testID={testId('capture.keep')}
              label={EN['capture.keep']}
              variant="secondary"
              onPress={() => setConfirmingDiscard(false)}
            />
          </View>
        </View>
      ) : null}
      <View style={styles.bottomBar}>
        <View style={styles.tray}>
          <TrayStrip items={items} pending={pending} onRemove={remove} />
        </View>
        {addToEntry === undefined ? (
          <FAB
            testID={testId('capture.next')}
            icon={ICONS.forward}
            label={EN['capture.next']}
            disabled={items.length === 0}
            onPress={() => router.push(APP_ROUTES.entryNew as Href)}
          />
        ) : (
          <FAB
            testID={testId('capture.addToEntry')}
            icon={ICONS.save}
            label={EN['capture.addToEntry']}
            disabled={items.length === 0 || pending > 0}
            loading={adding}
            onPress={() => addToExisting().catch(() => undefined)}
          />
        )}
      </View>
    </SafeAreaView>
  );
}

const CAPTURE_EDGES = ['top', 'bottom', 'left', 'right'] as const;
const CAPTURE_BACKGROUND = '#000000';

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: CAPTURE_BACKGROUND },
  surface: { flex: 1 },
  topBar: { position: 'absolute', top: SPACING.sm, left: SPACING.sm },
  bottomBar: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md, padding: SPACING.md },
  tray: { flex: 1 },
  row: { flexDirection: 'row', gap: SPACING.md, justifyContent: 'space-between' },
  panel: { gap: SPACING.md, padding: SPACING.lg },
  panelText: { fontSize: FONT_SIZE.headline },
});

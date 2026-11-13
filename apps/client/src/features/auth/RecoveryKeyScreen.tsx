import { useRouter } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { RECOVERY_WORD_COUNT } from '@kodakiko/core';
import { useAppContext } from '../../composition/app-context';
import {
  FULL_PERCENT,
  RECOVERY_CONFIRM_COUNT,
  RECOVERY_GRID_COLUMNS,
} from '../../constants/auth-constants';
import { APP_ROUTES } from '../../keys/routes';
import { listTestId, TEST_IDS } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { Body, Title } from '../../ui/Typography';
import { pickConfirmPositions } from './confirm-positions';
import { useSubmit } from './use-submit';

function WordGrid({ words }: { readonly words: readonly string[] }) {
  const { colors } = useTheme();
  return (
    <View style={styles.grid}>
      {words.map((word, index) => (
        <View key={index} style={styles.cell}>
          <Text style={[styles.number, { color: colors.textMuted }]}>{index + 1}</Text>
          <Text
            testID={listTestId(TEST_IDS.recoveryKeyWord, index + 1)}
            selectable
            style={[styles.word, { color: colors.text }]}
          >
            {word}
          </Text>
        </View>
      ))}
    </View>
  );
}

function ConfirmStep({ words }: { readonly words: readonly string[] }) {
  const { services, sodium } = useAppContext();
  const { colors } = useTheme();
  const router = useRouter();
  const positions = useMemo(
    () =>
      pickConfirmPositions(
        (length) => sodium.randomBytes(length),
        RECOVERY_WORD_COUNT,
        RECOVERY_CONFIRM_COUNT,
      ),
    [sodium],
  );
  const [typed, setTyped] = useState<readonly string[]>(positions.map(() => ''));
  const [mismatch, setMismatch] = useState(false);
  const { busy, submit } = useSubmit();

  const confirm = () => {
    const matches = positions.every(
      (position, index) => (typed[index] ?? '').trim().toLowerCase() === words[position - 1],
    );
    setMismatch(!matches);
    if (!matches) return;
    submit(async () => {
      await services.auth.confirmRecoveryKeySaved();
      router.replace(APP_ROUTES.root);
    });
  };

  return (
    <>
      <Title testID="recoveryKey.confirmTitle" text={EN['recoveryKey.confirmTitle']} />
      {mismatch ? (
        <Banner
          testID="recoveryKey.confirmWrong"
          kind="error"
          message={EN['recoveryKey.confirmWrong']}
        />
      ) : null}
      {positions.map((position, index) => (
        <View key={position}>
          <Text testID={listTestId(TEST_IDS.recoveryKeyConfirmLabel, index)} style={[styles.confirmLabel, { color: colors.text }]}>
            {EN['recoveryKey.confirmWord'](position)}
          </Text>
          <TextField
            testID={listTestId(TEST_IDS.recoveryKeyConfirmField, index)}
            label={EN['recoveryKey.confirmWord'](position)}
            showLabel={false}
            value={typed[index] ?? ''}
            onChangeText={(value) =>
              setTyped(typed.map((entry, entryIndex) => (entryIndex === index ? value : entry)))
            }
            onSubmit={confirm}
          />
        </View>
      ))}
      <Button
        testID="recoveryKey.confirmSubmit"
        label={EN['recoveryKey.confirmSubmit']}
        onPress={confirm}
        busy={busy}
      />
    </>
  );
}

export function RecoveryKeyScreen() {
  const { services } = useAppContext();
  const router = useRouter();
  const [words, setWords] = useState<readonly string[] | null>(null);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    let cancelled = false;
    services.auth
      .pendingRecoveryWords()
      .then((loaded) => {
        if (cancelled) return;
        if (loaded === null) router.replace(APP_ROUTES.root);
        else setWords(loaded);
      })
      .catch(() => router.replace(APP_ROUTES.root));
    return () => {
      cancelled = true;
    };
  }, [services, router]);

  return (
    <Screen testID="recoveryKey.screen" scroll>
      {words === null ? null : confirming ? (
        <ConfirmStep words={words} />
      ) : (
        <>
          <Title testID="recoveryKey.title" text={EN['recoveryKey.title']} />
          <Body testID="recoveryKey.body" text={EN['recoveryKey.body']} />
          <WordGrid words={words} />
          <Button
            testID="recoveryKey.done"
            label={EN['recoveryKey.done']}
            onPress={() => setConfirming(true)}
          />
        </>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap' },
  confirmLabel: { fontSize: FONT_SIZE.body, fontWeight: '600' },
  cell: {
    width: `${FULL_PERCENT / RECOVERY_GRID_COLUMNS}%`,
    flexDirection: 'row',
    gap: SPACING.sm,
    paddingVertical: SPACING.xs,
  },
  number: { fontSize: FONT_SIZE.body, minWidth: SPACING.xxl },
  word: { fontSize: FONT_SIZE.body, fontWeight: '600' },
});

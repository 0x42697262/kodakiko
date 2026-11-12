import { useMemo, useState } from 'react';
import { StyleSheet, Text } from 'react-native';
import { createFileStore } from '../../adapters/filestore/filestore';
import { createRowStore } from '../../adapters/rowstore/rowstore';
import { useAppContext } from '../../composition/app-context';
import { EN, type StaticTextKey } from '../../text/en';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { FONT_SIZE } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { measureKdf, runVectors } from './diagnostics-runs';
import { fileStoreSelfTest } from './filestore-self-test';
import { rowStoreSelfTest } from './rowstore-self-test';
import { enqueueUploadSelfTest, scheduleUploadSelfTestCleanup } from './upload-self-test';

type Action = { readonly key: StaticTextKey; readonly run: () => Promise<readonly string[]> };

function describeFailure(error: unknown): string {
  return EN['diagnostics.failed'](error instanceof Error ? error.message : String(error));
}

export function DiagnosticsScreen() {
  const app = useAppContext();
  const { sodium, lifecycle } = app;
  const rowStore = useMemo(() => createRowStore({ sodium }), [sodium]);
  const fileStore = useMemo(() => createFileStore({ sodium }), [sodium]);
  const { colors } = useTheme();
  const [busyKey, setBusyKey] = useState<StaticTextKey | null>(null);
  const [lines, setLines] = useState<readonly string[]>([]);

  const actions: readonly Action[] = [
    {
      key: 'diagnostics.runVectors',
      run: async () => {
        const results = await runVectors(sodium);
        const passed = results.filter((result) => result.passed).length;
        return [
          EN['diagnostics.vectorSummary'](passed, results.length),
          ...results.map((result) =>
            EN['diagnostics.vectorCase'](result.name, result.passed, result.durationMs),
          ),
        ];
      },
    },
    {
      key: 'diagnostics.measureKdf',
      run: async () => {
        const measurement = await measureKdf(sodium);
        return [
          EN['diagnostics.kdfSummary'](measurement.minMs, measurement.medianMs, measurement.maxMs),
          EN['diagnostics.kdfParams'](
            measurement.finalParams.opsLimit,
            measurement.finalParams.memLimitBytes,
          ),
        ];
      },
    },
    {
      key: 'diagnostics.rowStoreSelfTest',
      run: async () => {
        await rowStoreSelfTest(rowStore);
        return [EN['diagnostics.selfTestPassed'](EN['diagnostics.rowStoreSelfTest'])];
      },
    },
    {
      key: 'diagnostics.fileStoreSelfTest',
      run: async () => {
        await fileStoreSelfTest(fileStore);
        return [EN['diagnostics.selfTestPassed'](EN['diagnostics.fileStoreSelfTest'])];
      },
    },
    {
      key: 'sync.diagnosticsUploadTest',
      run: async () => {
        const state = app.accountState.get();
        if (state.kind !== 'unlocked') throw new Error(EN['keys.accountLocked']);
        const result = await enqueueUploadSelfTest({
          sodium,
          rowStore: app.rowStore,
          fileStore: app.fileStore,
          entryKeys: app.services.entryKeys,
          userId: state.userId,
          randomId: app.randomId,
          clock: app.clock,
        });
        scheduleUploadSelfTestCleanup(
          {
            rowStore: app.rowStore,
            uploads: app.services.uploads,
            clock: app.clock,
            onEnqueued: app.services.sync.notifyLocalWrite,
          },
          result.entryId,
        );
        app.services.sync.notifyLocalWrite();
        return [EN['sync.diagnosticsUploadTestResult'](result.objectId, result.sizeBytes)];
      },
    },
  ];

  async function execute(action: Action): Promise<void> {
    setBusyKey(action.key);
    setLines([EN['diagnostics.running']]);
    await lifecycle.afterInteractions();
    try {
      setLines(await action.run());
    } catch (error) {
      setLines([describeFailure(error)]);
    } finally {
      setBusyKey(null);
    }
  }

  return (
    <Screen testID="diagnostics.screen" scroll>
      <Text style={[styles.title, { color: colors.text }]}>{EN['diagnostics.title']}</Text>
      {actions.map((action) => (
        <Button
          key={action.key}
          testID={action.key}
          label={EN[action.key]}
          busy={busyKey === action.key}
          disabled={busyKey !== null}
          onPress={() => void execute(action)}
        />
      ))}
      {lines.map((line, position) => (
        <Text
          key={`${position}-${line}`}
          testID={`diagnostics.line.${position}`}
          style={[styles.line, { color: colors.text }]}
        >
          {line}
        </Text>
      ))}
    </Screen>
  );
}

const styles = StyleSheet.create({
  title: { fontSize: FONT_SIZE.title, fontWeight: '600' },
  line: { fontSize: FONT_SIZE.caption },
});

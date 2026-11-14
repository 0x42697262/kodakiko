import { useState } from 'react';
import { useAppContext } from '../../composition/app-context';
import { pickExportDestination } from '../../adapters/export-destination/export-destination';
import {
  ExportCancelledError,
  type ExportProgress,
  type ExportSummary,
} from '../../services/export/export-service';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Body } from '../../ui/Typography';
import { formatBytes } from '../format/format-bytes';
import { SettingsPage, SwitchRow } from './settings-ui';
import { startKdfParams } from '@kodakiko/core';
import { encryptedDestination } from '../../services/export/encrypted-destination';
import { TextField } from '../../ui/TextField';

const MIN_PASSPHRASE = 12;

const PHASE_LABELS: Readonly<Record<ExportProgress['phase'], string>> = {
  planning: EN['settings.phasePlanning'],
  downloading: EN['settings.phaseDownloading'],
  writing: EN['settings.phaseWriting'],
  done: EN['settings.phaseDone'],
};

export function ExportScreen() {
  const { services, sodium } = useAppContext();
  const [progress, setProgress] = useState<ExportProgress | null>(null);
  const [summary, setSummary] = useState<ExportSummary | null>(null);
  const [downloads, setDownloads] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const running = progress !== null && summary === null;
  const [encrypt, setEncrypt] = useState(false);
  const [passphrase, setPassphrase] = useState('');
  const [confirm, setConfirm] = useState('');
  const passphraseProblem = !encrypt
    ? null
    : passphrase.length < MIN_PASSPHRASE
      ? EN['export.passphraseShort']
      : passphrase !== confirm
        ? EN['export.passphraseMismatch']
        : null;

  async function start(): Promise<void> {
    setMessage(null);
    setSummary(null);
    if (passphraseProblem !== null) return;
    const choice = await pickExportDestination();
    if (choice.kind === 'cancelled') return;
    setDownloads(choice.downloads);
    setProgress({
      phase: 'planning',
      entriesDone: 0,
      entriesTotal: 0,
      bytesWritten: 0,
      partNumber: 0,
      partsTotal: 0,
    });
    try {
      const params = startKdfParams(new Uint8Array(0));
      const destination = encrypt
        ? await encryptedDestination(sodium, choice.destination, passphrase, {
            opsLimit: params.opsLimit,
            memLimitBytes: params.memLimitBytes,
          })
        : choice.destination;
      setSummary(await services.exporter.run(destination, setProgress));
    } catch (error) {
      setMessage(
        error instanceof ExportCancelledError
          ? EN['settings.exportCancelled']
          : EN['settings.exportFailed'],
      );
      setProgress(null);
    }
  }

  const skipped =
    summary === null
      ? 0
      : summary.skippedMissing + summary.skippedTooLarge + summary.skippedUnreadable;
  return (
    <SettingsPage testID="settings.exportScreen" title={EN['settings.rowExport']}>
      <Body text={encrypt ? EN['export.explainEncrypted'] : EN['settings.exportExplain']} />
      <Body text={EN['settings.exportKeepOpen']} />
      {downloads ? <Body testID="export.downloads" text={EN['settings.exportDownloads']} /> : null}
      {running && progress !== null ? (
        <Body
          testID="export.progress"
          text={EN['settings.exportProgress'](
            PHASE_LABELS[progress.phase],
            progress.entriesDone,
            progress.entriesTotal,
            progress.partNumber,
            progress.partsTotal,
            formatBytes(progress.bytesWritten),
          )}
        />
      ) : null}
      {summary === null ? null : (
        <Banner
          testID="export.summary"
          kind="info"
          message={EN['settings.exportDone'](summary.parts, formatBytes(summary.bytes))}
        />
      )}
      {skipped > 0 ? (
        <Body testID="export.skipped" text={EN['settings.exportSkipped'](skipped)} />
      ) : null}
      {message === null ? null : (
        <Banner testID="export.message" kind="warning" message={message} />
      )}
      {running ? null : (
        <>
          <SwitchRow
            testID="export.encrypt"
            label={EN['export.encrypt']}
            hint={EN['export.encryptSummary']}
            value={encrypt}
            onChange={setEncrypt}
          />
          {encrypt ? (
            <>
              <TextField
                testID="export.passphrase"
                label={EN['export.passphrase']}
                value={passphrase}
                onChangeText={setPassphrase}
                secureTextEntry
              />
              <TextField
                testID="export.passphraseConfirm"
                label={EN['export.passphraseConfirm']}
                value={confirm}
                onChangeText={setConfirm}
                secureTextEntry
                errorText={passphrase.length > 0 ? passphraseProblem : null}
              />
            </>
          ) : null}
        </>
      )}
      {running ? (
        <Button
          testID="export.cancel"
          label={EN['settings.exportCancel']}
          variant="secondary"
          onPress={() => services.exporter.cancel()}
        />
      ) : (
        <Button
          testID="export.start"
          label={EN['settings.exportStart']}
          onPress={() => start().catch(() => undefined)}
        />
      )}
    </SettingsPage>
  );
}

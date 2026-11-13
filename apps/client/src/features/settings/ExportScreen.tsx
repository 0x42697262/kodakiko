import { useState } from 'react';
import { useAppContext } from '../../composition/app-context';
import { pickExportDestination } from '../../adapters/export-destination/export-destination';
import { ExportCancelledError, type ExportProgress, type ExportSummary } from '../../services/export/export-service';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Body } from '../../ui/Typography';
import { formatBytes } from '../format/format-bytes';
import { SettingsPage } from './settings-ui';

const PHASE_LABELS: Readonly<Record<ExportProgress['phase'], string>> = {
  planning: EN['settings.phasePlanning'],
  downloading: EN['settings.phaseDownloading'],
  writing: EN['settings.phaseWriting'],
  done: EN['settings.phaseDone'],
};

export function ExportScreen() {
  const { services } = useAppContext();
  const [progress, setProgress] = useState<ExportProgress | null>(null);
  const [summary, setSummary] = useState<ExportSummary | null>(null);
  const [downloads, setDownloads] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const running = progress !== null && summary === null;

  async function start(): Promise<void> {
    setMessage(null);
    setSummary(null);
    const choice = await pickExportDestination();
    if (choice.kind === 'cancelled') return;
    setDownloads(choice.downloads);
    setProgress({ phase: 'planning', entriesDone: 0, entriesTotal: 0, bytesWritten: 0, partNumber: 0, partsTotal: 0 });
    try {
      setSummary(await services.exporter.run(choice.destination, setProgress));
    } catch (error) {
      setMessage(error instanceof ExportCancelledError ? EN['settings.exportCancelled'] : EN['settings.exportFailed']);
      setProgress(null);
    }
  }

  const skipped = summary === null ? 0 : summary.skippedMissing + summary.skippedTooLarge + summary.skippedUnreadable;
  return (
    <SettingsPage testID="settings.exportScreen" title={EN['settings.rowExport']}>
      <Body text={EN['settings.exportExplain']} />
      <Body text={EN['settings.exportKeepOpen']} />
      {downloads ? <Body testID="export.downloads" text={EN['settings.exportDownloads']} /> : null}
      {running && progress !== null ? (
        <Body
          testID="export.progress"
          text={EN['settings.exportProgress'](PHASE_LABELS[progress.phase], progress.entriesDone, progress.entriesTotal, progress.partNumber, progress.partsTotal, formatBytes(progress.bytesWritten))}
        />
      ) : null}
      {summary === null ? null : <Banner testID="export.summary" kind="info" message={EN['settings.exportDone'](summary.parts, formatBytes(summary.bytes))} />}
      {skipped > 0 ? <Body testID="export.skipped" text={EN['settings.exportSkipped'](skipped)} /> : null}
      {message === null ? null : <Banner testID="export.message" kind="warning" message={message} />}
      {running ? (
        <Button testID="export.cancel" label={EN['settings.exportCancel']} variant="secondary" onPress={() => services.exporter.cancel()} />
      ) : (
        <Button testID="export.start" label={EN['settings.exportStart']} onPress={() => start().catch(() => undefined)} />
      )}
    </SettingsPage>
  );
}

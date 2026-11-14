import { useState } from 'react';
import { pickImportFiles } from '../../adapters/import-picker/pick-import-files';
import { useAppContext } from '../../composition/app-context';
import { ImportPassphraseError, type ImportProgress, type ImportSummary } from '../../services/import/journal-import';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Body } from '../../ui/Typography';
import { SettingsPage } from './settings-ui';

export function ImportScreen() {
  const { services } = useAppContext();
  const [passphrase, setPassphrase] = useState('');
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<ImportProgress | null>(null);
  const [summary, setSummary] = useState<ImportSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function start(): Promise<void> {
    setError(null);
    setSummary(null);
    const files = await pickImportFiles();
    if (files === null) return;
    setBusy(true);
    try {
      setSummary(await services.journalImport.run(files, passphrase === '' ? null : passphrase, setProgress));
    } catch (failure) {
      setError(failure instanceof ImportPassphraseError ? EN['import.passphraseWrong'] : EN['import.error']);
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }

  return (
    <SettingsPage testID="import.screen" title={EN['import.title']}>
      <Body text={EN['import.explain']} />
      <TextField testID="import.passphrase" label={EN['import.passphrase']} value={passphrase} onChangeText={setPassphrase} secureTextEntry />
      {progress === null ? null : <Body testID="import.progress" text={EN['import.progress'](progress.done, progress.total)} />}
      {summary === null ? null : <Banner testID="import.summary" kind="info" message={EN['import.done'](summary.entries, summary.media, summary.duplicates)} />}
      {summary !== null && summary.failed.length > 0 ? <Body testID="import.failed" text={EN['import.failed'](summary.failed.length)} /> : null}
      {error === null ? null : <Banner testID="import.error" kind="error" message={error} />}
      <Button testID="import.start" label={EN['import.choose']} busy={busy} onPress={() => start().catch(() => undefined)} />
    </SettingsPage>
  );
}

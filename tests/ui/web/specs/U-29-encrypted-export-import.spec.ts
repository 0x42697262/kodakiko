import { readFile } from 'node:fs/promises';
import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const EXPORT_WAIT_MS = 180_000;
const PASSPHRASE = 'a long export passphrase';

let source: SeededAccount;
let target: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-29 runs in the desktop project only.');
  const project = testInfo.project.name as UiProject;
  source = await seedAccount({ username: uiUsername('U-29', project, 0) });
  target = await seedAccount({ username: uiUsername('U-29', project, 1) });
  await seedEntries(source, [
    { takenAtUtc: '2026-02-01T08:00:00.000Z', offsetMinutes: 0, note: 'first', tags: ['kept'], media: ['photo-gps.jpg'] },
    { takenAtUtc: '2026-02-02T08:00:00.000Z', offsetMinutes: 0, note: 'second', tags: [], media: ['photo-portrait.jpg'] },
  ]);
});

test('U-29 an encrypted export imports into another account with the passphrase', async ({ browser }) => {
  test.setTimeout(EXPORT_WAIT_MS * 2);
  const exporting = await browser.newContext();
  const page = await exporting.newPage();
  await page.addInitScript(() => {
    delete (window as { showDirectoryPicker?: unknown }).showDirectoryPicker;
  });
  await signInThroughUi(page, source);
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(2, { timeout: SYNC_WAIT_MS });
  await page.getByTestId('tabs.more').click();
  await page.getByTestId('more.dataStorage').click();
  await page.getByTestId('settings.row.export').click();
  await page.getByTestId('export.encrypt').click();
  await page.getByTestId('export.passphrase').fill(PASSPHRASE);
  await page.getByTestId('export.passphraseConfirm').fill(PASSPHRASE);
  const files: { name: string; buffer: Buffer }[] = [];
  page.on('download', (download) => {
    void download.path().then(async (path) => {
      if (path !== null) files.push({ name: download.suggestedFilename(), buffer: await readFile(path) });
    });
  });
  await page.getByTestId('export.start').click();
  await expect(page.getByTestId('export.summary')).toBeVisible({ timeout: EXPORT_WAIT_MS });
  await expect.poll(() => files.length, { timeout: EXPORT_WAIT_MS }).toBeGreaterThan(0);
  expect(files.every((file) => file.name.endsWith('.kdkx'))).toBe(true);
  await exporting.close();

  const importing = await browser.newContext();
  const other = await importing.newPage();
  await signInThroughUi(other, target);
  await expect(other.getByTestId('gallery.capture')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await other.getByTestId('tabs.more').click();
  await other.getByTestId('more.dataStorage').click();
  await other.getByTestId('settings.row.import').click();
  await other.getByTestId('import.passphrase').fill(PASSPHRASE);
  const chooser = other.waitForEvent('filechooser');
  await other.getByTestId('import.start').click();
  await (await chooser).setFiles(files.map((file) => ({ name: file.name, mimeType: 'application/octet-stream', buffer: file.buffer })));
  await expect(other.getByTestId('import.summary')).toContainText('Imported 2 entries', { timeout: EXPORT_WAIT_MS });
  await other.locator('[data-testid="settings.back"]:visible').click();
  await other.locator('[data-testid="settings.back"]:visible').click();
  await other.getByTestId('tabs.gallery').click();
  await expect(other.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(2, { timeout: SYNC_WAIT_MS });
  await importing.close();
});

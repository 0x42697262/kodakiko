import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { unzipSync } from 'fflate';
import { FIXTURES_DIRECTORY } from '../../support/constants.ts';
import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const EXPORT_WAIT_MS = 120_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-20 runs in the desktop project only (07c §8).');
  account = await seedAccount({ username: uiUsername('U-20', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-04-01T08:00:00.000Z', offsetMinutes: 0, note: 'first', tags: [], media: ['photo-gps.jpg'] },
    { takenAtUtc: '2026-04-02T08:00:00.000Z', offsetMinutes: 0, note: 'second', tags: [], media: ['photo-portrait.jpg'] },
  ]);
});

test('U-20 the export falls back to downloads and every zip holds the entries and media bytes', async ({ page }) => {
  test.setTimeout(EXPORT_WAIT_MS * 2);
  await page.addInitScript(() => {
    delete (window as { showDirectoryPicker?: unknown }).showDirectoryPicker;
  });
  await signInThroughUi(page, account);
  await expect(page.getByTestId(/^gallery\.tile\.\d+$/)).toHaveCount(2, { timeout: SYNC_WAIT_MS });
  await page.getByTestId('tabs.more').click();
  await page.getByTestId('more.settings').click();
  await page.getByTestId('settings.row.storage').click();
  await page.getByTestId('settings.row.export').click();
  const downloads: Uint8Array[] = [];
  page.on('download', (download) => {
    void download.path().then(async (path) => {
      if (path !== null) downloads.push(new Uint8Array(await readFile(path)));
    });
  });
  await page.getByTestId('export.start').click();
  await expect(page.getByTestId('export.summary')).toBeVisible({ timeout: EXPORT_WAIT_MS });
  await expect.poll(() => downloads.length, { timeout: EXPORT_WAIT_MS }).toBeGreaterThan(0);
  const files: Record<string, Uint8Array> = {};
  for (const zip of downloads) Object.assign(files, unzipSync(zip));
  expect(Object.keys(files).filter((name) => name.endsWith('/entry.md'))).toHaveLength(2);
  const media = Object.entries(files).filter(([name]) => name.endsWith('.jpg')).map(([, bytes]) => bytes);
  const expected = [
    new Uint8Array(await readFile(join(FIXTURES_DIRECTORY, 'photo-gps.jpg'))),
    new Uint8Array(await readFile(join(FIXTURES_DIRECTORY, 'photo-portrait.jpg'))),
  ];
  for (const fixture of expected) expect(media.some((bytes) => Buffer.from(bytes).equals(Buffer.from(fixture)))).toBe(true);
});

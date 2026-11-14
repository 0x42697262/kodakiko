import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const TILES = /^gallery\.tile\.\d+$/;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-35 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-35', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-11-03T10:00:00.000Z', offsetMinutes: 0, note: 'one', tags: [], media: ['photo-gps.jpg'] },
    { takenAtUtc: '2026-11-04T10:00:00.000Z', offsetMinutes: 0, note: 'two', tags: [], media: ['photo-portrait.jpg'] },
  ]);
});

test('U-35 a caption shows on its photo in the edit page and the viewer, and search finds it', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId(TILES)).toHaveCount(2, { timeout: SYNC_WAIT_MS });
  await page.getByTestId('gallery.tile.0').click();
  await page.getByTestId('viewer.edit').click();
  const editor = page.getByTestId('edit.screen');
  await editor.getByTestId('edit.mediaMenu.0').click();
  await page.getByTestId('edit.caption').click();
  await page.getByTestId('edit.captionField').fill('Heron at dawn');
  await page.getByTestId('edit.captionSave').click();
  await expect(editor.getByTestId('edit.captionText.0')).toHaveText('Heron at dawn');
  await editor.getByTestId('compose.back').click();
  await expect(page.getByTestId('viewer.caption')).toHaveText('Heron at dawn');

  await page.getByTestId('viewer.back').click();
  await page.getByTestId('gallery.search').click();
  await page.getByTestId('gallery.searchField').fill('heron');
  await expect(page.getByTestId(TILES)).toHaveCount(1);
});

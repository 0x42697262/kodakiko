import { addFiles, expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({ username: uiUsername('U-07', testInfo.project.name as UiProject, 0) });
});

test('U-07 an entry saved offline shows as pending, syncs when online and reaches another session', async ({
  page,
  context,
  browser,
}) => {
  await signInThroughUi(page, account);
  await page.getByTestId('gallery.capture').click();
  await addFiles(page, ['photo-gps.jpg', 'video-20s.mp4']);
  await expect(page.getByTestId('capture.trayItem.1')).toBeVisible({ timeout: 60_000 });
  await page.getByTestId('capture.next').click();
  await page.getByTestId('compose.note').fill('Picnic');
  await context.setOffline(true);
  await page.getByTestId('compose.save').click();

  await expect(page.getByTestId('gallery.tilePending.0')).toBeVisible();
  await context.setOffline(false);
  await expect(page.getByTestId('gallery.tilePending.0')).toBeHidden({ timeout: SYNC_WAIT_MS });

  const other = await browser.newContext();
  const otherPage = await other.newPage();
  await signInThroughUi(otherPage, account);
  await expect(otherPage.getByTestId('gallery.tile.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await other.close();
});

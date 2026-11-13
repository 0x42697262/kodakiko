import { addFiles, expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({ username: uiUsername('U-06', testInfo.project.name as UiProject, 0) });
});

test('U-06 capture two files, save with a note and a tag, open the entry in the viewer', async ({ page }) => {
  await signInThroughUi(page, account);
  await page.getByTestId('gallery.capture').click();
  await addFiles(page, ['photo-gps.jpg', 'video-20s.mp4']);
  await expect(page.getByTestId('capture.trayItem.1')).toBeVisible({ timeout: 60_000 });
  await page.getByTestId('capture.next').click();
  await page.getByTestId('compose.note').fill('Picnic');
  await page.getByTestId('tags.field').fill('family');
  await page.getByTestId('tags.field').press('Enter');
  await page.getByTestId('compose.save').click();

  await expect(page.getByTestId('gallery.tile.0')).toBeVisible();
  await expect(page.locator('[data-testid="gallery.screen"]')).toHaveCount(1);
  await expect(page.locator('[data-testid="capture.screen"]')).toHaveCount(0);
  await page.getByTestId('gallery.tile.0').click();
  await expect(page.getByTestId('viewer.pageIndicator')).toHaveText('1 / 2');
  await expect(page.getByTestId('location.coordinates')).toContainText('48.85840, 2.29450');
});

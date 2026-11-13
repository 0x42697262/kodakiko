import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const WHEEL_UP = -300;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-12 web runs in the desktop project only (07c §8).');
  account = await seedAccount({ username: uiUsername('U-12', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    {
      takenAtUtc: '2026-05-01T08:00:00.000Z',
      offsetMinutes: 0,
      note: '',
      tags: [],
      media: ['photo-gps.jpg', 'photo-portrait.jpg'],
    },
  ]);
});

test('U-12 double-click and Ctrl+wheel zoom a photo; arrows change photo when not zoomed', async ({ page }) => {
  await signInThroughUi(page, account);
  await page.getByTestId('gallery.tile.0').click({ timeout: SYNC_WAIT_MS });
  const indicator = page.getByTestId('viewer.pageIndicator');
  const photo = page.getByTestId('viewer.zoomable.0');
  await expect(indicator).toHaveText('1 / 2');

  await photo.dblclick();
  await expect(photo).toHaveAttribute('aria-label', 'Photo 1, zoomed');
  await photo.dblclick();
  await expect(photo).toHaveAttribute('aria-label', 'Photo 1');

  await photo.hover();
  await page.keyboard.down('Control');
  await page.mouse.wheel(0, WHEEL_UP);
  await page.keyboard.up('Control');
  await expect(photo).toHaveAttribute('aria-label', 'Photo 1, zoomed');
  await photo.dblclick();
  await expect(photo).toHaveAttribute('aria-label', 'Photo 1');

  await page.keyboard.press('ArrowRight');
  await expect(indicator).toHaveText('2 / 2');
});

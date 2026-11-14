import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { readServerMediaIds } from '../../support/server-state.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const DRAG_X = 150;
const DRAG_Y = 100;
const DRAG_STEPS = 8;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', 'U-37 runs in the mobile project only.');
  account = await seedAccount({ username: uiUsername('U-37', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [{ takenAtUtc: '2026-11-03T10:00:00.000Z', offsetMinutes: 0, note: 'crop me', tags: [], media: ['photo-gps.jpg'] }]);
});

test('U-37 cropping a photo replaces it with the cropped copy (D-97)', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  const before = await readServerMediaIds(account);
  await page.getByTestId('gallery.tile.0').click();
  await page.getByTestId('viewer.edit').click();
  await page.getByTestId('edit.mediaMenu.0').click();
  await page.getByTestId('edit.crop').click();
  const handle = page.getByTestId('crop.bottomRight');
  await expect(handle).toBeVisible({ timeout: SYNC_WAIT_MS });
  const box = await handle.boundingBox();
  if (box === null) throw new Error('crop handle has no box');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x - DRAG_X, box.y - DRAG_Y, { steps: DRAG_STEPS });
  await page.mouse.up();
  await page.getByTestId('crop.apply').click();
  await expect(page.getByTestId('crop.sheet')).toHaveCount(0, { timeout: SYNC_WAIT_MS });
  await expect(page.getByTestId('edit.page.0')).toBeVisible();

  await expect
    .poll(async () => {
      const after = await readServerMediaIds(account);
      return after.length === 1 && !before.includes(after[0] ?? '');
    }, { timeout: SYNC_WAIT_MS })
    .toBe(true);
});

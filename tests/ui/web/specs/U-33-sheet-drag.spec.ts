import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const DRAG_PX = 300;
const DRAG_STEPS = 12;
const SETTLE_MS = 600;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', 'U-33 runs in the mobile project only.');
  account = await seedAccount({ username: uiUsername('U-33', testInfo.project.name as UiProject, 0) });
});

test('U-33 dragging the sheet handle down closes the sheet; a short drag springs back', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('gallery.capture')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('gallery.filter').click();
  const sheet = page.getByTestId('filter.sheet');
  await expect(sheet).toBeVisible();
  await page.waitForTimeout(SETTLE_MS);
  const box = await sheet.boundingBox();
  if (box === null) throw new Error('sheet has no box');
  const x = box.x + box.width / 2;
  const y = box.y + 10;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x, y + 30, { steps: 4 });
  await page.mouse.up();
  await expect(sheet).toBeVisible();
  await page.waitForTimeout(SETTLE_MS);
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x, y + DRAG_PX, { steps: DRAG_STEPS });
  await page.mouse.up();
  await expect(sheet).toHaveCount(0);
});

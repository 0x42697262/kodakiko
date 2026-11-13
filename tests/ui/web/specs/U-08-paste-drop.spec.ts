import {
  dropFile,
  expect,
  openRouteInApp,
  pasteFile,
  signInThroughUi,
  test,
} from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const CAPTURE_ROUTE = '/capture';

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({
    username: uiUsername('U-08', testInfo.project.name as UiProject, 0),
  });
});

test('U-08 a pasted screenshot and a dropped photo join the tray', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('home.signedInAs')).toBeVisible();
  await openRouteInApp(page, CAPTURE_ROUTE);
  await expect(page.getByTestId('capture.dropZone')).toBeVisible();

  await pasteFile(page, 'screenshot.png');
  await expect(page.getByTestId('capture.trayItem.0')).toBeVisible();

  await dropFile(page, 'photo-portrait.jpg');
  await expect(page.getByTestId('capture.trayItem.1')).toBeVisible();
});

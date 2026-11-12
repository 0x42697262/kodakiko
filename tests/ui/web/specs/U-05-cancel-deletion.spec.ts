import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedDeletionRequest, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({
    username: uiUsername('U-05', testInfo.project.name as UiProject, 0),
  });
  await seedDeletionRequest(account);
});

test('U-05 a pending deletion is cancelled with Keep My Account', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('cancelDeletion.title')).toBeVisible();

  await page.getByTestId('cancelDeletion.submit').click();

  await expect(page.getByTestId('home.signedInAs')).toContainText(account.username);
});

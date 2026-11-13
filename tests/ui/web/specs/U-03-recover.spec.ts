import { UI_TEST_NEW_PASSWORD, UI_TEST_PASSWORD } from '../../support/constants.ts';
import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({
    username: uiUsername('U-03', testInfo.project.name as UiProject, 0),
  });
});

test('U-03 recover with the 24 words and sign in with the new password', async ({ page }) => {
  await page.goto('/sign-in');
  await page.getByTestId('signIn.forgot').click();
  await page.getByTestId('recover.username').fill(account.username);
  await page.getByTestId('recover.words').fill(account.recoveryWords.join(' '));
  await page.getByTestId('recover.newPassword').fill(UI_TEST_NEW_PASSWORD);
  await page.getByTestId('recover.confirm').fill(UI_TEST_NEW_PASSWORD);
  await page.getByTestId('recover.submit').click();

  await expect(page.getByTestId('home.signedInAs')).toContainText(account.username);
  await page.getByTestId('gallery.more').click();
  await page.getByTestId('home.signOut').click();

  await signInThroughUi(page, { username: account.username, password: UI_TEST_PASSWORD });
  await expect(page.getByTestId('errors.invalidCredentials')).toBeVisible();

  await signInThroughUi(page, { username: account.username, password: UI_TEST_NEW_PASSWORD });
  await expect(page.getByTestId('home.signedInAs')).toContainText(account.username);
});

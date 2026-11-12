import { WRONG_TEST_PASSWORD } from '../../support/constants.ts';
import { expect, signInThroughUi, test, unlockThroughUi } from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({
    username: uiUsername('U-04', testInfo.project.name as UiProject, 0),
  });
});

test('U-04 a reload locks the journal; the password unlocks it', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('home.signedInAs')).toBeVisible();

  await page.reload();
  await expect(page.getByTestId('lock.title')).toBeVisible();

  await unlockThroughUi(page, WRONG_TEST_PASSWORD);
  await expect(page.getByTestId('lock.wrongPassword')).toBeVisible();

  await unlockThroughUi(page, account.password);
  await expect(page.getByTestId('home.signedInAs')).toContainText(account.username);
});

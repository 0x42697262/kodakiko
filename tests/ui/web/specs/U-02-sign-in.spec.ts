import { UI_TEST_PASSWORD, WRONG_TEST_PASSWORD } from '../../support/constants.ts';
import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

let account: SeededAccount;
let unknownUsername: string;

test.beforeAll(async ({}, testInfo) => {
  const project = testInfo.project.name as UiProject;
  account = await seedAccount({ username: uiUsername('U-02', project, 0) });
  unknownUsername = uiUsername('U-02', project, 1);
});

test('U-02 sign in: wrong password and unknown username get the same answer', async ({ page }) => {
  await signInThroughUi(page, { username: account.username, password: WRONG_TEST_PASSWORD });
  const wrongPassword = page.getByTestId('errors.invalidCredentials');
  await expect(wrongPassword).toBeVisible();
  const wrongPasswordText = await wrongPassword.innerText();
  expect(wrongPasswordText).not.toBe('');

  await signInThroughUi(page, { username: unknownUsername, password: UI_TEST_PASSWORD });
  const unknownUser = page.getByTestId('errors.invalidCredentials');
  await expect(unknownUser).toBeVisible();
  expect(await unknownUser.innerText()).toBe(wrongPasswordText);

  await signInThroughUi(page, account);
  await expect(page.getByTestId('home.signedInAs')).toContainText(account.username);
});

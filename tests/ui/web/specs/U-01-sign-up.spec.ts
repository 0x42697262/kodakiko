import { RECOVERY_WORD_COUNT, UI_TEST_PASSWORD } from '../../support/constants.ts';
import {
  confirmRecoveryWords,
  expect,
  readRecoveryWords,
  signUpThroughUi,
  test,
} from '../../support/fixtures.ts';
import { seedInvite } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

test('U-01 sign up with an invite, write down and confirm the recovery key', async ({
  page,
}, testInfo) => {
  const username = uiUsername('U-01', testInfo.project.name as UiProject, 0);
  const invite = await seedInvite('member');

  await signUpThroughUi(page, invite, { username, password: UI_TEST_PASSWORD });
  const words = await readRecoveryWords(page);
  expect(words).toHaveLength(RECOVERY_WORD_COUNT);
  await page.getByTestId('recoveryKey.done').click();
  await confirmRecoveryWords(page, words);
  await page.getByTestId('recoveryKey.confirmSubmit').click();

  await expect(page.getByTestId('home.signedInAs')).toContainText(username);
});

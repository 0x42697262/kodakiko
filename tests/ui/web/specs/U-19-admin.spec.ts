import { UI_TEST_PASSWORD } from '../../support/constants.ts';
import { expect, signInThroughUi, signUpThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const INVITE_TOKEN = /#(kdk_i_[A-Za-z0-9_-]+)/;

let admin: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-19 runs in the desktop project only (07c §8).');
  admin = await seedAccount({ username: uiUsername('U-19', testInfo.project.name as UiProject, 0), role: 'admin' });
});

test('U-19 an admin invites a new user and sets their quota', async ({ page, browser }, testInfo) => {
  const newcomer = uiUsername('U-19', testInfo.project.name as UiProject, 1);
  await signInThroughUi(page, admin);
  await expect(page.getByTestId('gallery.capture')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('tabs.more').click();
  await page.getByTestId('more.settings').click();
  await page.getByTestId('settings.row.admin').click();
  await page.getByTestId('admin.segment.invites').click();
  await page.getByTestId('admin.newInvite').click();
  await page.getByTestId('admin.createInvite').click();
  const url = (await page.getByTestId('admin.inviteUrl').textContent())?.trim() ?? '';
  const token = INVITE_TOKEN.exec(url)?.[1] ?? '';
  expect(token).not.toBe('');

  const other = await browser.newContext();
  const otherPage = await other.newPage();
  await signUpThroughUi(otherPage, { url, token }, { username: newcomer, password: UI_TEST_PASSWORD });
  await expect(otherPage.getByTestId('recoveryKey.done')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await other.close();

  await page.keyboard.press('Escape');
  await page.getByTestId('admin.segment.server').click();
  await page.getByTestId('admin.segment.people').click();
  const row = page.getByTestId(/^admin\.userRow\.\d+$/).filter({ hasText: newcomer });
  await expect(row).toBeVisible({ timeout: SYNC_WAIT_MS });
  await row.click();
  await page.getByTestId('admin.useDefault').click();
  await page.getByTestId('admin.quota').fill('1');
  await page.getByTestId('admin.save').click();
  await expect(page.getByTestId(/^admin\.userRow\.\d+$/).filter({ hasText: newcomer })).toContainText('of 1 GB');
});

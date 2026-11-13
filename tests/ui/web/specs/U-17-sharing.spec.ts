import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, seedShare, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const TILES = /^gallery\.tile\.\d+$/;
const SYNC_WAIT_MS = 60_000;
const JOURNAL_NAME = 'Picnics';
const VISIBLE_BACK = '[data-testid="settings.back"]:visible';

let owner: SeededAccount;
let recipient: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-17 runs in the desktop project only (07c §8).');
  const project = testInfo.project.name as UiProject;
  owner = await seedAccount({ username: uiUsername('U-17', project, 0) });
  recipient = await seedAccount({ username: uiUsername('U-17', project, 1) });
  const created = await seedEntries(owner, [
    { takenAtUtc: '2026-05-03T08:00:00.000Z', offsetMinutes: 0, note: 'one', tags: [], media: ['photo-gps.jpg'] },
    { takenAtUtc: '2026-05-04T08:00:00.000Z', offsetMinutes: 0, note: 'two', tags: [], media: ['photo-portrait.jpg'] },
  ]);
  await seedShare(owner, recipient, { name: JOURNAL_NAME, entryIds: created.map((entry) => entry.entryId) });
});

test('U-17 a recipient browses a shared journal read-only, codes match, and leaving removes it', async ({ browser }) => {
  const ownerContext = await browser.newContext();
  const recipientContext = await browser.newContext();
  const ownerPage = await ownerContext.newPage();
  const recipientPage = await recipientContext.newPage();

  await signInThroughUi(recipientPage, recipient);
  await recipientPage.getByTestId('tabs.shared').click();
  const row = recipientPage.getByTestId('shared.withMeRow.0');
  await expect(row).toContainText(JOURNAL_NAME, { timeout: SYNC_WAIT_MS });
  await row.click();
  await expect(recipientPage.getByTestId(TILES)).toHaveCount(2, { timeout: SYNC_WAIT_MS });
  await recipientPage.getByTestId('gallery.tile.0').click();
  await expect(recipientPage.getByTestId('viewer.pageIndicator')).toBeVisible();
  await expect(recipientPage.getByTestId('viewer.edit')).toHaveCount(0);
  await recipientPage.getByTestId('viewer.back').click();

  await signInThroughUi(ownerPage, owner);
  await ownerPage.getByTestId('tabs.shared').click();
  await ownerPage.getByTestId('shared.mineRow.0').click();
  await ownerPage.getByTestId('collection.more').click();
  await ownerPage.getByTestId('collection.shareWith').click();
  await ownerPage.getByTestId('share.username').fill(recipient.username);
  await ownerPage.getByTestId('share.find').click();
  const seenByOwner = await ownerPage.getByTestId('share.securityCode').textContent();

  await recipientPage.getByTestId('shared.back').click();
  await recipientPage.getByTestId('tabs.gallery').click();
  await recipientPage.getByTestId('tabs.more').click();
  await recipientPage.getByTestId('more.settings').click();
  await recipientPage.getByTestId('settings.row.account').click();
  const ownCode = await recipientPage.getByTestId('settings.securityCode').textContent();
  expect(seenByOwner?.trim()).toBeTruthy();
  expect(ownCode?.trim()).toBe(seenByOwner?.trim());

  await recipientPage.locator(VISIBLE_BACK).click();
  await recipientPage.locator(VISIBLE_BACK).click();
  await recipientPage.getByTestId('tabs.shared').click();
  await recipientPage.getByTestId('shared.withMeRow.0').click();
  await recipientPage.getByTestId('collection.more').click();
  await recipientPage.getByTestId('collection.leave').click();
  await recipientPage.getByTestId('collection.leaveConfirm').click();
  await expect(recipientPage.getByTestId('shared.emptyWithMe')).toBeVisible({ timeout: SYNC_WAIT_MS });

  await ownerContext.close();
  await recipientContext.close();
});

import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { galleryFilterEntries } from '../../support/gallery-entries.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const TILES = /^gallery\.tile\.\d+$/;
const DAY_HEADERS = /^gallery\.dayHeader\.\d+$/;
const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({
    username: uiUsername('U-10', testInfo.project.name as UiProject, 0),
  });
  await seedEntries(account, galleryFilterEntries(Date.now()));
});

test('U-10 the gallery groups by day and narrows by tags (AND) and by note search', async ({
  page,
}) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('home.signedInAs')).toBeVisible();
  await expect(page.getByTestId(TILES)).toHaveCount(3, { timeout: SYNC_WAIT_MS });
  await expect(page.getByTestId(DAY_HEADERS)).toHaveCount(3);

  await page.getByTestId('gallery.filter').click();
  await page.getByTestId('tags.field').fill('a');
  await page.getByTestId('tags.field').press('Enter');
  await page.getByTestId('tags.field').fill('b');
  await page.getByTestId('tags.field').press('Enter');
  await page.getByTestId('filter.apply').click();
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible();
  await expect(page.getByTestId(TILES)).toHaveCount(1);

  await page.getByTestId('gallery.clearAll').click();
  await expect(page.getByTestId(TILES)).toHaveCount(3);

  await page.getByTestId('gallery.search').click();
  await page.getByTestId('gallery.searchField').fill('lake');
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible();
  await expect(page.getByTestId(TILES)).toHaveCount(1);
});

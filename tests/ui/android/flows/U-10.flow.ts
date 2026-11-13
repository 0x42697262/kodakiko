import { galleryFilterEntries } from '../../support/gallery-entries.ts';
import { seedAccount, seedEntries } from '../../support/seed.ts';
import { uiUsername } from '../../support/usernames.ts';

export default {
  seed: async () => {
    const account = await seedAccount({ username: uiUsername('U-10', 'android', 0) });
    await seedEntries(account, galleryFilterEntries(Date.now()));
    return { USERNAME: account.username, PASSWORD: account.password };
  },
  parts: [{ file: 'U-10-gallery-filters.yaml' }],
};

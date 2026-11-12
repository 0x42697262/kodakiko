import { seedAccount, seedDeletionRequest } from '../../support/seed.ts';
import { uiUsername } from '../../support/usernames.ts';

export default {
  seed: async () => {
    const account = await seedAccount({ username: uiUsername('U-05', 'android', 0) });
    await seedDeletionRequest(account);
    return { USERNAME: account.username, PASSWORD: account.password };
  },
  parts: [{ file: 'U-05-cancel-deletion.yaml' }],
};

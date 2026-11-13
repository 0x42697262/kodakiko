import { seedAccount } from '../../support/seed.ts';
import { uiUsername } from '../../support/usernames.ts';

export default {
  seed: async () => {
    const account = await seedAccount({ username: uiUsername('U-09', 'android', 0) });
    return { USERNAME: account.username, PASSWORD: account.password };
  },
  parts: [{ file: 'U-09-camera-capture.yaml' }],
};

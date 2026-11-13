import { seedAccount } from '../../support/seed.ts';
import { uiUsername } from '../../support/usernames.ts';

const PAST_ONE_MINUTE_MS = 70_000;

export default {
  seed: async () => {
    const account = await seedAccount({ username: uiUsername('U-15', 'android', 0) });
    return { USERNAME: account.username, PASSWORD: account.password };
  },
  parts: [
    { file: 'U-15-p1.yaml', waitAfterMs: PAST_ONE_MINUTE_MS },
    { file: 'U-15-p2.yaml' },
  ],
};
